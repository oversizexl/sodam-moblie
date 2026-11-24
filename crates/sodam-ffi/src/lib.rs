//! Android 登录层 FFI：二维码登录全流程 + Cookie 校验。
//!
//! 刻意只依赖 `libresoda`，不依赖 `sodam-core`——后者无条件引入了 rodio，登录阶段用不到。
//! 这样本 crate 可以直接交叉编译到 Android，先在最容易出问题的登录链路上真机验证。
//!
//! 二次验证页地址由本机回环 HTTP 服务托管，Kotlin 侧把它放进应用内 WebView（或系统浏览器）打开。
//! **不要调用 `Soda::open_second_verify`**：它最后一步经 `open` crate 拉起系统浏览器，Android 不支持。

use std::collections::{BTreeMap, HashMap, HashSet};
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex};

use libresoda::model::{QRLoginStatus, Song};
use libresoda::soda::download::song_track_id;
use libresoda::soda::media_ref::MediaRef;
use libresoda::soda::qr_login::QrDevice;
use libresoda::soda::signature::HttpSignature;
use libresoda::soda::track::build_song_from_track;
use libresoda::soda::types::build_image_url;
use libresoda::soda::user_playlist::fetch_pc_me;
use libresoda::Soda;
use sodam_core::config::{DEFAULT_SIGNER_TOKEN, DEFAULT_SIGNER_URL};

uniffi::setup_scaffolding!();

#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum SodaError {
    /// 传输层 / JSON / 解密等非鉴权错误。
    #[error("网络错误: {reason}")]
    Network { reason: String },
    /// 鉴权失败。`1000006` 单独成句，提示用户用汽水音乐 App 扫码。
    #[error("登录态无效: {reason}")]
    Auth { reason: String },
    /// 本地状态（设备身份文件、未启用验证页等）。
    #[error("内部错误: {reason}")]
    Internal { reason: String },
}

impl From<libresoda::SodaError> for SodaError {
    fn from(err: libresoda::SodaError) -> Self {
        match err {
            libresoda::SodaError::Api {
                status_code,
                status_msg,
            } => {
                let reason = if status_code == 1_000_006 {
                    "该登录态不属于汽水音乐 App（1000006 拒绝），请用汽水音乐 App 扫码登录。"
                        .to_string()
                } else {
                    status_msg
                };
                SodaError::Auth { reason }
            }
            other => {
                if other.is_cookie_required() {
                    // 未登录 / 登录态缺失：归为鉴权错误，界面据此引导登录，而不是当网络错误反复重试。
                    SodaError::Auth {
                        reason: "未登录，请先登录".to_string(),
                    }
                } else {
                    let reason = if other.is_signer_failure() {
                        "签名服务暂时不可用，请稍后重试".to_string()
                    } else {
                        other.to_string()
                    };
                    SodaError::Network { reason }
                }
            }
        }
    }
}

#[derive(Debug, uniffi::Record)]
pub struct QrLogin {
    pub token: String,
    /// 服务端原样下发的 `qrcode_index_url`。用它渲染二维码，不要改写成 scan_login。
    pub scan_url: String,
    /// 服务端下发的二维码图片（data URL）。可直接显示，省掉客户端渲染。
    pub qr_image: String,
    /// 过期时间（Unix 秒；0 = 未知）。
    pub expire_time: i64,
}

#[derive(Debug, Clone, Copy, uniffi::Enum)]
pub enum LoginState {
    Waiting,
    Scanned,
    NeedSecondVerify,
    Success,
    Expired,
    Failed,
}

#[derive(Debug, uniffi::Record)]
pub struct LoginProgress {
    pub state: LoginState,
    pub message: String,
    /// 仅 `Success` 时非空。凭据——调用方不得打印或写入日志。
    pub cookie: String,
    pub rate_limited: bool,
}

#[derive(Debug, uniffi::Record)]
pub struct AccountInfo {
    pub user_id: String,
    pub nickname: String,
    pub vip: bool,
    /// 头像原图地址；可能为空（无头像/接口没下发）。
    pub avatar: String,
}

#[derive(Debug, uniffi::Record)]
pub struct Track {
    pub id: String,
    pub name: String,
    pub artist: String,
    pub album: String,
    pub duration: i64,
    pub cover: String,
    pub vip: bool,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct PlaylistSummary {
    pub id: String,
    pub name: String,
    pub cover: String,
    pub track_count: i64,
    pub creator: String,
}

/// 搜索结果里的音乐人。
#[derive(Debug, Clone, uniffi::Record)]
pub struct ArtistSummary {
    pub id: String,
    pub name: String,
    pub avatar: String,
    pub track_count: i64,
    /// 关注人数。
    pub follower_count: i64,
}

/// 搜索结果里的专辑。
#[derive(Debug, Clone, uniffi::Record)]
pub struct AlbumSummary {
    pub id: String,
    pub name: String,
    pub cover: String,
    pub artist: String,
    pub track_count: i64,
}

/// 综合搜索结果。四类结果来自同一次带签名的 PC 综合请求。
#[derive(Debug, uniffi::Record)]
pub struct SearchResults {
    pub tracks: Vec<Track>,
    /// 官方的「你可能想搜」：综合结果最前面的相关音乐人。
    pub top_artists: Vec<ArtistSummary>,
    pub artists: Vec<ArtistSummary>,
    pub albums: Vec<AlbumSummary>,
    pub playlists: Vec<PlaylistSummary>,
}

/// 发现页的一组推荐歌单（按服务端的分组标题分类）。
#[derive(Debug, Clone, uniffi::Record)]
pub struct PlaylistGroup {
    pub title: String,
    pub playlists: Vec<PlaylistSummary>,
}

/// 听歌模式条目（对齐桌面端 `SceneItem`）。
#[derive(Debug, Clone, uniffi::Record)]
pub struct Scene {
    pub text: String,
    pub entry_type: String,
    pub scene_mode_id: i64,
    pub sub_queue_type: String,
    pub cover: String,
}

/// 下载并解密后的本地文件，可直接交给 Media3 播放。
#[derive(Debug, uniffi::Record)]
pub struct ResolvedTrack {
    pub path: String,
    /// 实际拉到的音质（例：`无损 871k`）。
    pub quality: String,
    /// 只是试听片段（账号无整曲权益或没配签名）。
    pub preview: bool,
}

/// 一行歌词（已从 LRC 解析出起始毫秒）。
#[derive(Debug, Clone, uniffi::Record)]
pub struct LyricLine {
    pub start_ms: i64,
    pub text: String,
}

#[derive(serde::Deserialize, Default)]
struct PcWebSearchResponse {
    #[serde(default)]
    status_code: i64,
    #[serde(default)]
    status_info: PcWebStatus,
    #[serde(default)]
    result_groups: Vec<PcWebGroup>,
}

#[derive(serde::Deserialize, Default)]
struct PcWebStatus {
    #[serde(default)]
    status_msg: String,
}

#[derive(serde::Deserialize, Default)]
struct PcWebGroup {
    #[serde(default)]
    id: String,
    #[serde(default)]
    data: Vec<PcWebItem>,
}

#[derive(serde::Deserialize, Default)]
struct PcWebItem {
    #[serde(default)]
    entity: PcWebEntity,
}

/// 综合搜索的 `entity` 里只有其中一种类型。
#[derive(serde::Deserialize, Default)]
struct PcWebEntity {
    #[serde(default)]
    track: Option<libresoda::soda::types::Track>,
    #[serde(default)]
    artist: Option<libresoda::soda::types::Artist>,
    #[serde(default)]
    album: Option<libresoda::soda::types::Album>,
    #[serde(default)]
    playlist: Option<libresoda::soda::types::UserPlaylistItem>,
}

/// 推荐流里的 `track` JSON → `Song`（字段对齐桌面端 `recommended_tracks`）。
fn song_from_track_json(track: &serde_json::Value) -> Option<Song> {
    let id = track
        .get("id")
        .and_then(|value| value.as_str())
        .unwrap_or_default()
        .trim()
        .to_string();
    if id.is_empty() {
        return None;
    }
    let name = track
        .get("name")
        .and_then(|value| value.as_str())
        .unwrap_or("未知曲目")
        .to_string();
    let artist = track
        .get("artists")
        .and_then(|value| value.as_array())
        .map(|artists| {
            artists
                .iter()
                .filter_map(|artist| artist.get("name").and_then(|value| value.as_str()))
                .collect::<Vec<_>>()
                .join(" / ")
        })
        .unwrap_or_default();
    let album = track
        .pointer("/album/name")
        .and_then(|value| value.as_str())
        .unwrap_or_default()
        .to_string();
    let album_id = track
        .pointer("/album/id")
        .and_then(|value| value.as_str())
        .unwrap_or_default()
        .to_string();
    let duration = (track
        .get("duration")
        .and_then(|value| value.as_f64())
        .unwrap_or_default()
        / 1000.0)
        .round() as i64;
    let vip = track
        .pointer("/label_info/only_vip_playable")
        .and_then(|value| value.as_bool())
        .unwrap_or(false);
    let cover = track
        .pointer("/album/url_cover")
        .and_then(|image| {
            let _ = image.get("urls")?.as_array()?.first()?.as_str()?;
            let uri = image.get("uri")?.as_str()?;
            let template = image.get("template_prefix")?.as_str()?;
            Some(format!(
                "https://p3-luna.douyinpic.com/img/{uri}~{template}-resize:512:512.png"
            ))
        })
        .unwrap_or_default();
    let mut extra = BTreeMap::new();
    extra.insert("track_id".to_string(), id.clone());
    Some(Song {
        id,
        name,
        artist,
        album,
        album_id,
        duration,
        cover,
        source: "soda".to_string(),
        extra,
        is_vip: vip,
        ..Default::default()
    })
}

#[derive(uniffi::Object)]
pub struct SodaClient {
    soda: Arc<Soda>,
    cache_dir: PathBuf,
    /// 最近一次搜索/歌单里的曲目。`resolve_track` 靠它拿到完整 `Song`。
    songs: Mutex<HashMap<String, Song>>,
    /// 听歌模式变动不频繁，缓存一次，避免每次点开都等网络。
    scenes_cache: Mutex<Option<Vec<Scene>>>,
    /// 推荐分页计数（同一场景连续取页时递增，切换场景归零）。
    recommend_counter: Mutex<u64>,
    last_recommend_key: Mutex<Option<(i64, String)>>,
    /// 发现页已展示过的歌单卡片 id，用于向后端要下一批。
    discover_seen: Mutex<Vec<String>>,
    /// 音频缓存上限（字节，0 = 不限制）。
    cache_limit: Mutex<u64>,
}

/// 查回本地表里的原始 `Song`：收藏接口需要曲目媒体的 `Song::id`，而 UI 的 id 是
/// `song_track_id`（可能带额外 track_id），不能直接当媒体 id 用。
fn song_for(client: &SodaClient, track_id: &str) -> Result<Song, SodaError> {
    let table = client.songs.lock().unwrap_or_else(|err| err.into_inner());
    table.get(track_id).cloned().ok_or(SodaError::Internal {
        reason: "曲目不在本地列表里，请先播放或打开歌单".to_string(),
    })
}

#[uniffi::export]
impl SodaClient {
    /// `device_file`：Android 传 `context.filesDir/qr-device.json`。
    /// `cache_dir`：Android 传 `context.cacheDir/audio`，下载/解密的落盘位置。
    ///
    /// 设备身份要跨二维码复用，否则护照服务会把每次都当新设备，容易限流成 `error_code=7`。
    #[uniffi::constructor]
    pub fn new(device_file: String, cache_dir: String) -> Result<Arc<Self>, SodaError> {
        let soda = Soda::new("");
        // 装上 HttpLogin：它负责在 127.0.0.1 起验证页服务并代发页面请求。
        soda.enable_http_login();
        // 搜索/取流需要应用签名；沿用桌面端的内置签名服务。
        soda.set_signature_provider(Arc::new(
            HttpSignature::new(DEFAULT_SIGNER_URL).with_token(DEFAULT_SIGNER_TOKEN),
        ));
        let device = load_or_generate_device(Path::new(&device_file)).map_err(|err| {
            SodaError::Internal {
                reason: format!("登录设备身份读写失败: {err}"),
            }
        })?;
        soda.set_qr_device(device)?;
        let cache_dir = PathBuf::from(cache_dir);
        std::fs::create_dir_all(&cache_dir).map_err(|err| SodaError::Internal {
            reason: format!("缓存目录不可用: {err}"),
        })?;
        Ok(Arc::new(Self {
            soda: Arc::new(soda),
            cache_dir,
            songs: Mutex::new(HashMap::new()),
            scenes_cache: Mutex::new(None),
            recommend_counter: Mutex::new(0),
            last_recommend_key: Mutex::new(None),
            discover_seen: Mutex::new(Vec::new()),
            cache_limit: Mutex::new(0),
        }))
    }

    pub fn create_qr(&self) -> Result<QrLogin, SodaError> {
        let qr = self.soda.create_qr()?;
        Ok(QrLogin {
            token: qr.token,
            scan_url: qr.scan_url,
            qr_image: qr.qr_image,
            expire_time: qr.expire_time,
        })
    }

    pub fn check_qr(&self, token: String) -> Result<LoginProgress, SodaError> {
        let result = self.soda.check_qr(&token)?;
        let flag = |key: &str| result.extra.get(key).map(String::as_str) == Some("true");
        let state = if flag("need_second_verify") {
            LoginState::NeedSecondVerify
        } else {
            match result.status {
                QRLoginStatus::Waiting => LoginState::Waiting,
                QRLoginStatus::Scanned => LoginState::Scanned,
                QRLoginStatus::Success => LoginState::Success,
                QRLoginStatus::Expired => LoginState::Expired,
                QRLoginStatus::Failed => LoginState::Failed,
            }
        };
        Ok(LoginProgress {
            state,
            message: result.message,
            cookie: result.cookie,
            rate_limited: flag("rate_limited"),
        })
    }

    /// 二次验证页地址，形如 `http://127.0.0.1:{port}/security_host.html?key=...&bridgeRoot=/verify`。
    ///
    /// 仅在 `check_qr` 返回 `NeedSecondVerify` 后调用。页面里的官方组件请求由本进程代发。
    pub fn second_verify_url(&self, token: String) -> Result<String, SodaError> {
        self.soda.second_verify_url(&token).map_err(Into::into)
    }

    /// 校验一串 Cookie：成功后返回账号信息。
    ///
    /// 区分 `1000016`（失效）与 `1000006`（会话有效但 app 不对），避免把无效凭据写进配置。
    /// 持久化由调用方负责——本函数不改动任何已保存的配置。
    pub fn verify_cookie(&self, cookie: String) -> Result<AccountInfo, SodaError> {
        self.soda.set_cookie(cookie);
        let me = fetch_pc_me(&self.soda)?;
        let info = me.my_info;
        let vip =
            info.is_vip || matches!(info.vip_stage.to_ascii_lowercase().as_str(), "vip" | "svip");
        let avatar = build_image_url(&info.larger_avatar_url, "");
        Ok(AccountInfo {
            user_id: info.id,
            nickname: info.nickname,
            vip,
            avatar,
        })
    }

    /// 搜索歌曲。结果同时记进本地曲目表，供 `resolve_track` 使用。
    ///
    /// libresoda 内部已统一走带应用签名的 PC 综合接口（没有签名器时才回退 Android 旧路径）。
    pub fn search(&self, keyword: String) -> Result<Vec<Track>, SodaError> {
        let songs = self.soda.search(&keyword)?;
        Ok(self.remember(songs))
    }

    /// 综合搜索：一次带签名的 PC 综合请求，同时返回歌曲 / 音乐人 / 专辑 / 歌单。
    pub fn search_all(&self, keyword: String) -> Result<SearchResults, SodaError> {
        let body = self.soda.fetch_search_all_body(&keyword, 1, 30)?;
        let response: PcWebSearchResponse =
            serde_json::from_slice(&body).map_err(|err| SodaError::Network {
                reason: format!("搜索 JSON 解析失败: {err}"),
            })?;
        if response.status_code != 0 {
            let msg = if response.status_info.status_msg.trim().is_empty() {
                "unknown error".to_string()
            } else {
                response.status_info.status_msg
            };
            return Err(SodaError::Network {
                reason: format!("搜索失败 status_code={} {msg}", response.status_code),
            });
        }

        let mut songs = Vec::new();
        let mut song_ids = HashSet::new();
        let mut artists = Vec::new();
        let mut artist_ids = HashSet::new();
        let mut top_artists = Vec::new();
        let mut top_artist_ids = HashSet::new();
        let mut albums = Vec::new();
        let mut album_ids = HashSet::new();
        let mut playlists = Vec::new();
        let mut playlist_ids = HashSet::new();

        for group in response.result_groups {
            // 官方综合页把最匹配的相关歌手放在 `top_results` 组，对应「你可能想搜」。
            let is_top = group.id == "top_results";
            for item in group.data {
                let entity = item.entity;
                if let Some(track) = entity.track.filter(|track| !track.id.is_empty()) {
                    let song = build_song_from_track(&track);
                    if song_ids.insert(song_track_id(&song)) {
                        songs.push(song);
                    }
                }
                if let Some(artist) = entity.artist.filter(|artist| !artist.id.is_empty()) {
                    let summary = ArtistSummary {
                        id: artist.id,
                        name: artist.name,
                        avatar: build_image_url(&artist.url_avatar, "~c5_300x300.jpg"),
                        track_count: artist.count_tracks,
                        follower_count: artist.stats.count_collected,
                    };
                    if is_top && top_artist_ids.insert(summary.id.clone()) {
                        top_artists.push(summary.clone());
                    }
                    if artist_ids.insert(summary.id.clone()) {
                        artists.push(summary);
                    }
                }
                if let Some(album) = entity.album.filter(|album| !album.id.is_empty()) {
                    if album_ids.insert(album.id.clone()) {
                        albums.push(AlbumSummary {
                            id: album.id,
                            name: album.name,
                            cover: build_image_url(&album.url_cover, "~c5_300x300.jpg"),
                            artist: libresoda::soda::types::join_track_artists(&album.artists),
                            track_count: album.count_tracks,
                        });
                    }
                }
                if let Some(playlist) = entity.playlist.filter(|playlist| !playlist.id.is_empty()) {
                    if playlist_ids.insert(playlist.id.clone()) {
                        let name = if playlist.public_title.is_empty() {
                            playlist.title.clone()
                        } else {
                            playlist.public_title.clone()
                        };
                        let creator = if playlist.owner.public_name.is_empty() {
                            playlist.owner.nickname.clone()
                        } else {
                            playlist.owner.public_name.clone()
                        };
                        playlists.push(PlaylistSummary {
                            id: playlist.id,
                            name,
                            cover: build_image_url(&playlist.url_cover, "~c5_300x300.jpg"),
                            track_count: playlist.count_tracks,
                            creator,
                        });
                    }
                }
            }
        }

        Ok(SearchResults {
            tracks: self.remember(songs),
            top_artists,
            artists,
            albums,
            playlists,
        })
    }

    /// 发现页推荐歌单：优先用官方发现流的歌单分组（按服务端标题分类），
    /// 没有分组时回退到「推荐歌单」接口。
    pub fn discover_playlists(&self) -> Result<Vec<PlaylistGroup>, SodaError> {
        // 发现流失败不致命：下面还有推荐歌单接口兜底。
        let value = self.soda.fetch_discover().ok();
        let mut groups = Vec::new();
        let mut seen = HashSet::new();
        let mut exposure = Vec::new();

        if let Some(blocks) = value
            .as_ref()
            .and_then(|value| value.get("blocks"))
            .and_then(serde_json::Value::as_array)
        {
            for block in blocks {
                let block_type = block
                    .get("type")
                    .and_then(serde_json::Value::as_str)
                    .unwrap_or_default();
                if block_type != "discover_feed_playlist" {
                    continue;
                }
                let title = block
                    .get("title")
                    .or_else(|| block.get("block_title"))
                    .and_then(serde_json::Value::as_str)
                    .unwrap_or("推荐歌单")
                    .trim()
                    .to_string();
                let mut playlists = Vec::new();
                for item in block
                    .get("inner_block")
                    .and_then(serde_json::Value::as_array)
                    .into_iter()
                    .flatten()
                {
                    if let Some(id) = item
                        .get("inner_block_id")
                        .and_then(serde_json::Value::as_str)
                    {
                        if !id.trim().is_empty() {
                            exposure.push(id.trim().to_string());
                        }
                    }
                    let Some(playlist) = item
                        .pointer("/entity/playlist")
                        .or_else(|| item.pointer("/resources/0/entity/playlist"))
                    else {
                        continue;
                    };
                    let Some(summary) = playlist_summary_from_value(playlist) else {
                        continue;
                    };
                    if !seen.insert(summary.id.clone()) {
                        continue;
                    }
                    playlists.push(summary);
                }
                if !playlists.is_empty() {
                    groups.push(PlaylistGroup { title, playlists });
                }
            }
        }

        *self
            .discover_seen
            .lock()
            .unwrap_or_else(|err| err.into_inner()) = exposure;

        if groups.is_empty() {
            let list = self.soda.get_recommend_playlists()?;
            let playlists: Vec<PlaylistSummary> = list
                .into_iter()
                .map(|playlist| PlaylistSummary {
                    id: playlist.id,
                    name: playlist.name,
                    cover: playlist.cover,
                    track_count: playlist.track_count,
                    creator: playlist.creator,
                })
                .collect();
            if !playlists.is_empty() {
                groups.push(PlaylistGroup {
                    title: "推荐歌单".to_string(),
                    playlists,
                });
            }
        }

        Ok(groups)
    }

    /// 发现页推荐歌单的下一批。首屏来自 `discover_playlists`，这里按已曝光卡片继续要。
    pub fn discover_playlists_more(&self) -> Result<Vec<PlaylistSummary>, SodaError> {
        let exposure: Vec<String> = self
            .discover_seen
            .lock()
            .unwrap_or_else(|err| err.into_inner())
            .clone();
        if exposure.is_empty() {
            return Ok(Vec::new());
        }
        let body = serde_json::json!({
            "block_type": "discover_playlist_mix",
            "sub_channel_id": -2,
            "exposure_radio_list": exposure,
        });
        let value = self.soda.fetch_discover_mix_body(&body)?;
        let mut out = Vec::new();
        let mut new_ids = Vec::new();
        for item in value
            .get("inner_block")
            .and_then(serde_json::Value::as_array)
            .into_iter()
            .flatten()
        {
            if let Some(id) = item
                .get("inner_block_id")
                .and_then(serde_json::Value::as_str)
            {
                if !id.trim().is_empty() {
                    new_ids.push(id.trim().to_string());
                }
            }
            let Some(playlist) = item
                .pointer("/entity/playlist")
                .or_else(|| item.pointer("/resources/0/entity/playlist"))
            else {
                continue;
            };
            if let Some(summary) = playlist_summary_from_value(playlist) {
                out.push(summary);
            }
        }
        if !new_ids.is_empty() {
            let mut seen = self
                .discover_seen
                .lock()
                .unwrap_or_else(|err| err.into_inner());
            for id in new_ids {
                if !seen.contains(&id) {
                    seen.push(id);
                }
            }
        }
        Ok(out)
    }

    /// 我创建的歌单（需要登录态）。
    pub fn my_playlists(&self) -> Result<Vec<PlaylistSummary>, SodaError> {
        let page = self.soda.get_my_playlists("", 100)?;
        Ok(page
            .playlists
            .into_iter()
            .map(|playlist| PlaylistSummary {
                id: playlist.id,
                name: playlist.name,
                cover: playlist.cover,
                track_count: playlist.track_count,
                creator: playlist.creator,
            })
            .collect())
    }

    /// 歌单内的曲目。
    pub fn playlist_tracks(&self, playlist_id: String) -> Result<Vec<Track>, SodaError> {
        let songs = self.soda.get_playlist_songs(&playlist_id)?;
        Ok(self.remember(songs))
    }

    /// 专辑内的曲目。
    pub fn album_tracks(&self, album_id: String) -> Result<Vec<Track>, SodaError> {
        let songs = self.soda.get_album_songs(&album_id)?;
        Ok(self.remember(songs))
    }

    /// 音乐人的热歌。libresoda 只给原始 JSON，这里按 `tracks` 数组解析。
    pub fn artist_tracks(&self, artist_id: String) -> Result<Vec<Track>, SodaError> {
        let value = self.soda.list_artist_tracks(&artist_id, "", 50)?;
        let status_code = value
            .get("status_code")
            .and_then(serde_json::Value::as_i64)
            .unwrap_or(0);
        if status_code != 0 {
            let msg = value
                .pointer("/status_info/status_msg")
                .and_then(serde_json::Value::as_str)
                .unwrap_or("unknown error");
            return Err(SodaError::Network {
                reason: format!("音乐人热歌失败 status_code={status_code} {msg}"),
            });
        }
        let songs = value
            .get("tracks")
            .and_then(serde_json::Value::as_array)
            .map(|tracks| {
                tracks
                    .iter()
                    .filter_map(|track| {
                        serde_json::from_value::<libresoda::soda::types::Track>(track.clone()).ok()
                    })
                    .filter(|track| !track.id.is_empty())
                    .map(|track| build_song_from_track(&track))
                    .collect::<Vec<_>>()
            })
            .unwrap_or_default();
        Ok(self.remember(songs))
    }

    /// 听歌模式（场景模式）列表。首次成功后缓存，后续打开直接返回。
    pub fn feed_scenes(&self) -> Result<Vec<Scene>, SodaError> {
        if let Some(cached) = self
            .scenes_cache
            .lock()
            .unwrap_or_else(|err| err.into_inner())
            .as_ref()
        {
            return Ok(cached.clone());
        }
        let mode = self.soda.feed_mode()?;
        let scenes: Vec<Scene> = mode
            .scenes()
            .into_iter()
            .map(|scene| Scene {
                text: scene.text,
                entry_type: scene.entry_type,
                scene_mode_id: scene.scene_mode_id,
                sub_queue_type: scene.sub_queue_type,
                cover: scene.cover_url,
            })
            .collect();
        *self
            .scenes_cache
            .lock()
            .unwrap_or_else(|err| err.into_inner()) = Some(scenes.clone());
        Ok(scenes)
    }

    /// 一页推荐队列。
    ///
    /// `scene_mode_id >= 0` 走听歌模式；否则 `sub_queue_type` 为空表示首页推荐
    /// （`familiar` / `fresh` 是本地熟悉/新鲜模式）。
    pub fn recommend(
        &self,
        scene_mode_id: i64,
        sub_queue_type: String,
    ) -> Result<Vec<Track>, SodaError> {
        let now = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map(|elapsed| elapsed.as_secs())
            .unwrap_or(0);

        // 同一场景连续取页时递增时长计数；切换场景从 1 重新开始。
        let key = (scene_mode_id, sub_queue_type.clone());
        {
            let mut last = self
                .last_recommend_key
                .lock()
                .unwrap_or_else(|err| err.into_inner());
            if last.as_ref() != Some(&key) {
                *self
                    .recommend_counter
                    .lock()
                    .unwrap_or_else(|err| err.into_inner()) = 0;
            }
            *last = Some(key);
        }
        let counter = {
            let mut slot = self
                .recommend_counter
                .lock()
                .unwrap_or_else(|err| err.into_inner());
            *slot += 1;
            *slot
        };
        let first = counter == 1;

        let mut body = serde_json::json!({
            "played_media": [],
            "did_first_use_time": now,
            "is_first_request": first,
            "is_did_first_request": first,
            "feed_counts": { "mix_session_count": counter },
        });
        if scene_mode_id >= 0 {
            body["feed_preference"] = serde_json::json!({ "scene_mode_id": scene_mode_id });
        } else if !sub_queue_type.trim().is_empty() {
            body["feed_preference"] =
                serde_json::json!({ "preference_mode": sub_queue_type.trim() });
        }

        let value = self.soda.fetch_feed_song_tab(&body)?;
        let items = value
            .get("items")
            .and_then(|value| value.as_array())
            .ok_or(SodaError::Internal {
                reason: "推荐响应缺少 items".to_string(),
            })?;
        let songs = items
            .iter()
            .filter(|item| item.get("type").and_then(|value| value.as_str()) == Some("track"))
            .filter_map(|item| item.pointer("/entity/track_wrapper/track"))
            .filter_map(song_from_track_json)
            .collect::<Vec<_>>();
        Ok(self.remember(songs))
    }

    /// 下载并解密到本地缓存，返回可直接交给播放器的文件路径。
    ///
    /// 已存在的缓存直接返回。`preview=true` 表示只拿到试听片段（无整曲权益或没配签名）。
    pub fn resolve_track(&self, track_id: String) -> Result<ResolvedTrack, SodaError> {
        let song = {
            let table = self.songs.lock().unwrap_or_else(|err| err.into_inner());
            table.get(&track_id).cloned()
        };
        let Some(song) = song else {
            // 曲目不在内存表里（例如启动时优先播放的本地缓存曲目）：直接按缓存文件名找回
            // 已落盘的音频，避免“曲目不在本地列表里”而无法播放/续播。
            let preferred_tag = quality_tag(&self.soda.quality_preference());
            if let Some(resolved) = find_cached_track(&self.cache_dir, &track_id, &preferred_tag) {
                return Ok(resolved);
            }
            return Err(SodaError::Internal {
                reason: "曲目不在本地列表里，请重新搜索或打开歌单".to_string(),
            });
        };

        let ext = song.ext.trim();
        let ext = if ext.is_empty() { "m4a" } else { ext };
        let tag = quality_tag(&self.soda.quality_preference());
        let path = self.cache_dir.join(format!("{track_id}-{tag}.{ext}"));
        if path.is_file() {
            let cached_label = std::fs::read_to_string(quality_sidecar(&path))
                .ok()
                .map(|text| text.trim().to_string())
                .filter(|text| !text.is_empty());
            let quality = match cached_label {
                Some(label) => format!("{label} · 已缓存"),
                None => "已缓存".to_string(),
            };
            return Ok(ResolvedTrack {
                path: path.to_string_lossy().into_owned(),
                quality,
                preview: false,
            });
        }

        std::fs::create_dir_all(&self.cache_dir).map_err(|err| SodaError::Internal {
            reason: format!("缓存目录不可用: {err}"),
        })?;
        let info = download_with_retry(&self.soda, &song, &path)?;
        let quality = describe_quality(&info);
        // 与音频文件放一起，缓存命中时还能显示真实档位（和桌面端一致）。
        let _ = std::fs::write(quality_sidecar(&path), &quality);
        let limit = *self
            .cache_limit
            .lock()
            .unwrap_or_else(|err| err.into_inner());
        enforce_cache_limit(&self.cache_dir, limit);
        Ok(ResolvedTrack {
            path: path.to_string_lossy().into_owned(),
            quality,
            preview: info.is_preview,
        })
    }

    /// 后台预取：把列表里前几首下载解密到缓存，点播时直接命中。立即返回。
    ///
    /// 尽力而为：失败不抛，点播时会再走一次 `resolve_track`。
    pub fn prefetch(&self, track_ids: Vec<String>) {
        let songs: Vec<Song> = {
            let table = self.songs.lock().unwrap_or_else(|err| err.into_inner());
            track_ids
                .iter()
                .filter_map(|id| table.get(id).cloned())
                .collect()
        };
        if songs.is_empty() {
            return;
        }
        let soda = Arc::clone(&self.soda);
        let dir = self.cache_dir.clone();
        let tag = quality_tag(&self.soda.quality_preference());
        let limit = *self
            .cache_limit
            .lock()
            .unwrap_or_else(|err| err.into_inner());
        std::thread::spawn(move || {
            for song in songs {
                let track_id = song_track_id(&song);
                let ext = song.ext.trim();
                let ext = if ext.is_empty() { "m4a" } else { ext };
                let path = dir.join(format!("{track_id}-{tag}.{ext}"));
                if path.is_file() {
                    continue;
                }
                let _ = std::fs::create_dir_all(&dir);
                if let Ok(info) = download_with_retry(&soda, &song, &path) {
                    let _ = std::fs::write(quality_sidecar(&path), describe_quality(&info));
                    enforce_cache_limit(&dir, limit);
                }
            }
        });
    }

    /// 设置音质档位：`""`/`auto` 不设上限；`hires`/`lossless`/`dolby`/`highest`/`medium`/`low`。
    pub fn set_quality_preference(&self, tier: String) {
        self.soda.set_quality_preference(tier);
    }

    /// 当前音质档位（空串表示自动）。
    pub fn quality_preference(&self) -> String {
        self.soda.quality_preference()
    }

    /// 喜欢一首歌：写入服务端「我喜欢的音乐」。
    ///
    /// `track_id` 是本地表的键（`song_track_id`）；收藏接口要的是曲目媒体的 `Song::id`，
    /// 所以查回原始 Song 再取 `id`。
    pub fn like_track(&self, track_id: String) -> Result<(), SodaError> {
        let song = song_for(self, &track_id)?;
        self.soda
            .collect_media(&[MediaRef::track(song.id.clone())])?;
        Ok(())
    }

    /// 取消喜欢。
    pub fn unlike_track(&self, track_id: String) -> Result<(), SodaError> {
        let song = song_for(self, &track_id)?;
        self.soda
            .uncollect_media(&[MediaRef::track(song.id.clone())])?;
        Ok(())
    }

    /// 取歌词（已解析成带起始时间的行）。
    ///
    /// 冷启动优先播放的缓存曲目不在内存曲目表里；歌词接口只按 `track_id` 取词，
    /// 缺表时用 track_id 构造最小 `Song` 兜底，避免“曲目不在本地列表里”看不到歌词。
    pub fn lyrics(&self, track_id: String) -> Result<Vec<LyricLine>, SodaError> {
        let song = {
            let table = self.songs.lock().unwrap_or_else(|err| err.into_inner());
            table.get(&track_id).cloned()
        }
        .unwrap_or_else(|| {
            let mut song = Song {
                id: track_id.clone(),
                ..Default::default()
            };
            song.extra.insert("track_id".to_string(), track_id);
            song
        });
        let raw = self.soda.get_lyrics(&song)?;
        Ok(parse_lrc(&raw))
    }

    /// 设置音频缓存上限（字节，0 = 不限制），设置后立即按需淘汰较旧的文件。
    pub fn set_cache_limit(&self, bytes: i64) {
        let limit = bytes.max(0) as u64;
        *self
            .cache_limit
            .lock()
            .unwrap_or_else(|err| err.into_inner()) = limit;
        enforce_cache_limit(&self.cache_dir, limit);
    }

    /// 当前音频缓存占用（字节）。
    pub fn cache_size_bytes(&self) -> i64 {
        directory_size(&self.cache_dir) as i64
    }

    /// 清空音频缓存，返回释放的字节数。
    pub fn clear_cache(&self) -> i64 {
        let freed = directory_size(&self.cache_dir);
        if let Ok(entries) = std::fs::read_dir(&self.cache_dir) {
            for entry in entries.flatten() {
                let path = entry.path();
                if path.is_file() {
                    let _ = std::fs::remove_file(&path);
                }
            }
        }
        freed as i64
    }

    /// 清空内存里的登录态。调用方负责删除自己保存的 Cookie 文件。
    pub fn logout(&self) {
        self.soda.set_cookie("");
        *self
            .scenes_cache
            .lock()
            .unwrap_or_else(|err| err.into_inner()) = None;
        *self
            .recommend_counter
            .lock()
            .unwrap_or_else(|err| err.into_inner()) = 0;
        *self
            .last_recommend_key
            .lock()
            .unwrap_or_else(|err| err.into_inner()) = None;
    }
}

impl SodaClient {
    /// 把 `Song` 记进本地表并映射成 UI 记录。
    fn remember(&self, songs: Vec<Song>) -> Vec<Track> {
        let mut table = self.songs.lock().unwrap_or_else(|err| err.into_inner());
        songs
            .into_iter()
            .map(|song| {
                let id = song_track_id(&song);
                let track = Track {
                    id: id.clone(),
                    name: song.name.clone(),
                    artist: song.artist.clone(),
                    album: song.album.clone(),
                    duration: song.duration,
                    cover: song.cover.clone(),
                    vip: song.is_vip,
                };
                table.insert(id, song);
                track
            })
            .collect()
    }
}

/// 解析 LRC。支持一行多个时间标签；没有时间标签时按纯文本逐行返回。
fn parse_lrc(raw: &str) -> Vec<LyricLine> {
    let mut lines = Vec::new();
    for line in raw.lines() {
        let line = line.trim();
        if line.is_empty() {
            continue;
        }
        let mut rest = line;
        let mut stamps = Vec::new();
        while let Some(stripped) = rest.strip_prefix('[') {
            let Some(end) = stripped.find(']') else { break };
            if let Some(ms) = parse_lrc_time(&stripped[..end]) {
                stamps.push(ms);
            }
            rest = &stripped[end + 1..];
        }
        let text = rest.trim();
        if text.is_empty() {
            continue;
        }
        for start_ms in stamps {
            lines.push(LyricLine {
                start_ms,
                text: text.to_string(),
            });
        }
    }
    if lines.is_empty() {
        return raw
            .lines()
            .map(str::trim)
            .filter(|line| !line.is_empty())
            .map(|line| LyricLine {
                start_ms: 0,
                text: line.to_string(),
            })
            .collect();
    }
    lines.sort_by_key(|line| line.start_ms);
    lines
}

fn parse_lrc_time(tag: &str) -> Option<i64> {
    let (minutes, rest) = tag.split_once(':')?;
    let minutes: i64 = minutes.trim().parse().ok()?;
    let (seconds, fraction) = match rest.split_once('.') {
        Some((seconds, fraction)) => (seconds, Some(fraction)),
        None => (rest, None),
    };
    let seconds: i64 = seconds.trim().parse().ok()?;
    let millis = fraction
        .map(|fraction| {
            let digits: String = fraction.chars().filter(char::is_ascii_digit).collect();
            match digits.len() {
                0 => 0,
                1 => digits.parse::<i64>().unwrap_or(0) * 100,
                2 => digits.parse::<i64>().unwrap_or(0) * 10,
                3 => digits.parse::<i64>().unwrap_or(0),
                _ => digits[..3].parse::<i64>().unwrap_or(0),
            }
        })
        .unwrap_or(0);
    Some(minutes * 60_000 + seconds * 1000 + millis)
}

/// 音频缓存总占用。
fn directory_size(dir: &Path) -> u64 {
    let Ok(entries) = std::fs::read_dir(dir) else {
        return 0;
    };
    entries
        .flatten()
        .filter_map(|entry| entry.metadata().ok())
        .filter(|meta| meta.is_file())
        .map(|meta| meta.len())
        .sum()
}

/// 超过上限时按最后修改时间从旧到新淘汰；跳过下载中的临时文件。
fn enforce_cache_limit(dir: &Path, limit: u64) {
    if limit == 0 {
        return;
    }
    let Ok(entries) = std::fs::read_dir(dir) else {
        return;
    };
    let mut files: Vec<(PathBuf, u64, std::time::SystemTime)> = Vec::new();
    let mut total = 0u64;
    for entry in entries.flatten() {
        let path = entry.path();
        if !path.is_file() {
            continue;
        }
        let name = path
            .file_name()
            .and_then(|name| name.to_str())
            .unwrap_or("");
        if name.ends_with(".part")
            || name.ends_with(".part.meta")
            || name.ends_with(".part.meta.tmp")
            || name.ends_with(".part.lock")
            || name.ends_with(".dec.tmp")
        {
            continue;
        }
        let Ok(meta) = entry.metadata() else { continue };
        let modified = meta.modified().unwrap_or(std::time::UNIX_EPOCH);
        total += meta.len();
        files.push((path, meta.len(), modified));
    }
    if total <= limit {
        return;
    }
    files.sort_by_key(|(_, _, modified)| *modified);
    for (path, len, _) in files {
        if total <= limit {
            break;
        }
        if std::fs::remove_file(&path).is_ok() {
            total = total.saturating_sub(len);
        }
    }
}

/// 把音质偏好归一成缓存文件名后缀；换档位自然 miss 并另存一份（对齐桌面端）。
fn quality_tag(preference: &str) -> String {
    match preference.trim().to_ascii_lowercase().as_str() {
        "" | "auto" | "best" => "auto".to_string(),
        "hi_res" | "hi-res" | "hires" => "hires".to_string(),
        "flac" | "lossless" => "lossless".to_string(),
        "dolby_atmos" | "dolby" => "dolby".to_string(),
        "hq" | "high" | "highest" => "highest".to_string(),
        "standard" | "320k" | "medium" => "medium".to_string(),
        "128k" | "saver" | "low" => "low".to_string(),
        other => other.to_string(),
    }
}

/// 音质旁注文件：与音频同目录，缓存命中时仍能显示真实档位。
fn quality_sidecar(path: &Path) -> PathBuf {
    let mut name = path.as_os_str().to_owned();
    name.push(".quality");
    PathBuf::from(name)
}

/// 在缓存目录里按 `{track_id}-{tag}.{ext}` 找回已落盘的音频。
///
/// 启动时优先播放的缓存曲目并不在内存 `songs` 表里；`resolve_track` 用它兜底，
/// 让这些曲目也能播放/续播。优先当前音质档位，其他档位命中也能播（曲目 id 为准）。
fn find_cached_track(
    cache_dir: &Path,
    track_id: &str,
    preferred_tag: &str,
) -> Option<ResolvedTrack> {
    let entries = std::fs::read_dir(cache_dir).ok()?;
    let mut best: Option<(u8, ResolvedTrack)> = None;
    for entry in entries.flatten() {
        let path = entry.path();
        let Ok(meta) = entry.metadata() else {
            continue;
        };
        if !meta.is_file() || meta.len() == 0 {
            continue;
        }
        let Some(ext) = path.extension().and_then(|ext| ext.to_str()) else {
            continue;
        };
        // 音频本体扩展名由曲目来源决定；这里只排除旁注、临时与备份文件。
        if matches!(ext, "quality" | "part" | "tmp" | "old" | "lock" | "meta") {
            continue;
        }
        let Some(stem) = path.file_stem().and_then(|stem| stem.to_str()) else {
            continue;
        };
        let Some((id, tag)) = sodam_core::cache::parse_audio_cache_stem(stem) else {
            continue;
        };
        if id != track_id {
            continue;
        }
        let label = std::fs::read_to_string(quality_sidecar(&path))
            .ok()
            .map(|text| text.trim().to_string())
            .filter(|text| !text.is_empty());
        let quality = match label {
            Some(label) => format!("{label} · 已缓存"),
            None => "已缓存".to_string(),
        };
        let preferred = u8::from(tag == preferred_tag);
        match &best {
            Some((top, _)) if *top >= preferred => {}
            _ => {
                best = Some((
                    preferred,
                    ResolvedTrack {
                        path: path.to_string_lossy().into_owned(),
                        quality,
                        preview: false,
                    },
                ))
            }
        }
    }
    best.map(|(_, resolved)| resolved)
}

/// 发现流/推荐接口里的歌单 JSON → UI 记录。
fn playlist_summary_from_value(playlist: &serde_json::Value) -> Option<PlaylistSummary> {
    let id = playlist
        .get("id")
        .and_then(serde_json::Value::as_str)
        .unwrap_or_default()
        .trim()
        .to_string();
    if id.is_empty() {
        return None;
    }
    let name = playlist
        .get("public_title")
        .or_else(|| playlist.get("title"))
        .and_then(serde_json::Value::as_str)
        .unwrap_or("歌单")
        .to_string();
    let cover = playlist
        .get("url_cover")
        .and_then(|image| {
            serde_json::from_value::<libresoda::soda::types::Image>(image.clone()).ok()
        })
        .map(|image| build_image_url(&image, "~c5_300x300.jpg"))
        .unwrap_or_default();
    let track_count = playlist
        .get("count_tracks")
        .and_then(serde_json::Value::as_i64)
        .unwrap_or(0);
    Some(PlaylistSummary {
        id,
        name,
        cover,
        track_count,
        creator: String::new(),
    })
}

/// 和桌面端 `describe_quality` 一致：实际档位 + 码率（例：`无损 850k`）。
///
/// 服务端 `DownloadInfo.quality` 只是档位键（如 `medium`），真正给人看的是
/// 质量分数对应的档名；码率单位是 bps。
fn describe_quality(info: &libresoda::soda::types::DownloadInfo) -> String {
    let bitrate = info.bitrate.max(0) / 1000;
    let tier = sodam_core::quality::tier_label(sodam_core::quality::stream_rank(info));
    if bitrate > 0 {
        format!("{tier} {bitrate}k")
    } else {
        tier.to_string()
    }
}

/// 下载并解密，签名服务偶发失败时短暂退避重试（最多 2 次）。
fn download_with_retry(
    soda: &Soda,
    song: &Song,
    path: &Path,
) -> std::result::Result<libresoda::soda::types::DownloadInfo, libresoda::SodaError> {
    let mut attempt = 0u32;
    loop {
        match soda.download_with_info(song, path) {
            Ok(info) => return Ok(info),
            Err(err) if err.is_signer_failure() && attempt < 2 => {
                attempt += 1;
                std::thread::sleep(std::time::Duration::from_millis(600 * attempt as u64));
            }
            Err(err) => return Err(err),
        }
    }
}

fn load_or_generate_device(path: &Path) -> std::io::Result<QrDevice> {
    if let Ok(text) = std::fs::read_to_string(path) {
        if let Ok(device) = serde_json::from_str::<QrDevice>(&text) {
            if device.is_valid() {
                return Ok(device);
            }
        }
    }
    let device = QrDevice::generate();
    if let Some(parent) = path.parent() {
        if !parent.as_os_str().is_empty() {
            std::fs::create_dir_all(parent)?;
        }
    }
    let text = serde_json::to_string(&device).map_err(std::io::Error::other)?;
    std::fs::write(path, text)?;
    Ok(device)
}
