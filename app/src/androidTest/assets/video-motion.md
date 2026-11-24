# 视频播放回归样本

`video-motion.mp4` 是 FFmpeg `testsrc2` 生成的 15 秒动态测试图，不包含外部素材。格式为 H.264 / yuv420p、320 × 180、15 fps，每秒一个关键帧，无音轨。

```powershell
ffmpeg -f lavfi -i testsrc2=size=320x180:rate=15:duration=15 -c:v libx264 -preset veryfast -crf 30 -pix_fmt yuv420p -g 15 -movflags +faststart video-motion.mp4
```

与旧的三秒色条样本相比，这个样本在画面中央也持续变化，并为截屏、后台恢复和页面重建留出足够时间。媒体测试分别检查解码表面和最终屏幕画面，并检查没有其他应用的弹窗覆盖预览。
