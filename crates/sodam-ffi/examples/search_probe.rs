//! 内容接口探针：不经过 Android，直接验证 `SodaClient` 的搜索/映射。
//!
//! `cargo run -p sodam-ffi --example search_probe`

fn main() {
    let client = sodam_ffi::SodaClient::new(
        "/tmp/sodam-probe-device.json".to_string(),
        "/tmp/sodam-probe-cache".to_string(),
    )
    .expect("初始化客户端失败");

    let results = client.search("周杰伦".to_string()).expect("搜索失败");
    println!("搜索到 {} 首", results.len());
    for track in results.iter().take(8) {
        println!(
            "- id={} name={} artist={} vip={} duration={}",
            track.id, track.name, track.artist, track.vip, track.duration
        );
    }
}
