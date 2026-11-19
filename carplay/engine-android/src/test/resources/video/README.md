# Screen decoder test data

All files here and the device clip were made with ffmpeg 9.0.1 (libx264 core 165) on macOS, 2026-11-19. ffmpeg is an
independent H.264 implementation: the JVM tests compare our `avcC` parser, framing and keyframe detection against it
(docs/LESSONS.md 46), never against output of the code under test.

## JVM test vectors (`AvcTest`, `FrameQueueTest`, `DecoderCoreTest`)

A two-frame 64x64 High-profile stream without B-frames (like the iPhone's), in an MP4 and as Annex B:

```sh
ffmpeg -f lavfi -i testsrc2=size=64x64:rate=60:duration=1 -frames:v 2 -c:v libx264 -profile:v high \
  -preset veryfast -bf 0 -g 60 -pix_fmt yuv420p -x264-params sliced-threads=0:threads=1 -movflags +faststart \
  tiny-64x64-2f.mp4
ffmpeg -i tiny-64x64-2f.mp4 -c copy -bsf:v h264_mp4toannexb -f h264 ffmpeg-64x64.h264
python3 -I mp4split.py tiny-64x64-2f.mp4 tiny   # writes tiny.avcc, tiny-frame0.lp, tiny-frame1.lp
mv tiny.avcc ffmpeg-64x64.avcc; mv tiny-frame0.lp ffmpeg-64x64-frame0.lp; mv tiny-frame1.lp ffmpeg-64x64-frame1.lp
ffprobe -v error -select_streams v -show_entries frame=pict_type,key_frame -of csv tiny-64x64-2f.mp4
# frame,1,I  /  frame,0,P
```

| File | What it is | SHA-256 |
|---|---|---|
| `ffmpeg-64x64.avcc` | the `avcC` box body from ffmpeg's MP4 muxer (41 bytes, 4-byte NAL lengths) | `aae5a94fb95c250cd6709256925e9aceb563f092595f3cc33af85323e4a8993d` |
| `ffmpeg-64x64-frame0.lp` | sample 0 from the MP4's `mdat` (length-prefixed SEI + IDR; ffprobe: I) | `5f05719fff64c2c1751e804983c2025bc0995b77b90266245fcf3834adc9d49f` |
| `ffmpeg-64x64-frame1.lp` | sample 1 (length-prefixed P slice; ffprobe: P) | `1254a5dab58f530f33b152ee53090b7ab6a8ffda675b117ba517f6bc343bde96` |
| `ffmpeg-64x64.h264` | the same stream as Annex B from `h264_mp4toannexb` (SPS and PPS inserted) | `7114fe9c387d940e8a138439897e311fd2c5fbb5aef664277a3953bb92d16e46` |
| `mp4split.py` | reads `stsd/avc1/avcC`, `stsz` and `stco` of a one-track MP4 and writes the record and each sample | |

## Device smoke clip (`src/androidTest/assets/video/`)

`testsrc2-1280x720-60fps-5s.h264`: 5 s, 300 pictures, 1280x720 at 60 fps, High profile, no B-frames, an IDR every
60 pictures, about 2 Mbit/s, with access unit delimiters so the smoke test can split it into pictures. SHA-256
`995794d0da992bb85527c4cfc455a225827f51048b717f71ff28b3b37d788457`.

```sh
ffmpeg -f lavfi -i testsrc2=size=1280x720:rate=60:duration=5 -c:v libx264 -profile:v high -preset veryfast \
  -tune zerolatency -bf 0 -g 60 -keyint_min 60 -pix_fmt yuv420p -b:v 2M -maxrate 2M -bufsize 1M \
  -bsf:v h264_metadata=aud=insert -f h264 testsrc2-1280x720-60fps-5s.h264
```

It is used only by `ScreenDecoderSmokeTest` (instrumented, on the Poco), never by the JVM tests. That test plays it
twice through the real decoder at 60 fps into an `ImageReader` surface: once as Annex B, once converted to an `avcC`
record and length-prefixed pictures as the iPhone sends them, and streams every `carplay.video.*` line back to
`am instrument -r`.
