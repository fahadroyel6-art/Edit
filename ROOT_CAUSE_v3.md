══════════════════════════════════════════════════════════════
 ROOT CAUSE — কেন 1-2 ঘণ্টার ভিডিও 1-2% এ আটকে যায়
══════════════════════════════════════════════════════════════

RC-1 ★ DOMINANT — Progress callback starvation (log flood)
──────────────────────────────────────────────────────────────
FFmpegEngine log callback (প্রতি FFmpeg লাইনে):
    logBuffer.add(line)
    if (logBuffer.size > 4000) logBuffer.removeAt(0)   ← O(n) shift!

  • ArrayList.removeAt(0) = 4000 element arraycopy প্রতিটা লাইনে
  • 3-ঘণ্টার ফাইলে ~100,000+ লাইন → 4×10^8 element move
  • তার উপরে DiagnosticLogger.warn() → প্রতিবার:
        _entries.value = ArrayList(buffer)   ← 3000-entry copy!
        appendText()                          ← open/write/close on disk!

ffmpeg-kit একই executor thread থেকে log + statistics দুটোই dispatch করে।
Log callback তার নিজের কাজে ব্যস্ত → statistics callback (progress) পালা
পায় না → UI 1-2% এ জমে যায়, অথচ FFmpeg ব্যাকগ্রাউন্ডে চলছেই।

কেন শুধু লম্বা ভিডিওতে? 2-3 সেকেন্ডের ক্লিপে 200 লাইন, 3-ঘণ্টায়
লক্ষাধিক। ঠিক এজন্যই ছোট ভিডিও ঠিক, বড় ভিডিও আটকে যায়। ★★★

RC-2  -movflags +faststart  (পুরো আউটপুট ফাইল rewrite)
──────────────────────────────────────────────────────────────
  moov atom সামনে আনতে FFmpeg শেষে গোটা mdat আরেকবার লেখে।
  4 GB আউটপুটে = অন্য 4 GB I/O, কোনো progress ছাড়া।
  ব্যবহারকারীর কাছে মনে হয় হ্যাং।   (>4GB এ কিছু build fail করে)

RC-3  Input access = ffmpeg-kit 'saf:' pseudo-protocol
──────────────────────────────────────────────────────────────
  saf: handler Java-stream ভিত্তিক, non-seekable প্রায়ই।
  concat + MP4 demuxer কে MUST seek করতে হয় (moov সাধারণত ফাইলের শেষে)।
  multi-GB এ pathological slow।  সঠিক পথ: /proc/self/fd/N (real fd,
  seekable, zero copy — FFmpeg তো same process-এই চলে)।

RC-4  -fflags +genpts with -c copy
──────────────────────────────────────────────────────────────
  অপ্রয়োজনীয়; demuxer কে timestamp generate করতে বাধ্য করে,
  concat এ buffering বাড়ায়।  -avoid_negative_ts make_zero যথেষ্ট।

RC-5  -map 0:v? -map 0:a?   (সব audio stream)
──────────────────────────────────────────────────────────────
  3-ঘণ্টার মুভিতে ২টা audio track থাকলে seg-1 এ ২টা, seg-2 এ ১টা
  → concat विफल।  দরকার explicit  0:v:0? / 0:a:0?

RC-6  Progress/state plumbing
──────────────────────────────────────────────────────────────
  • MergeService.runJob সব VideoInfo durationMs = 0 দিয়ে বানায়
  • totalDurationMs == 0 হলে progress কোথাও আপডেটই হয় না
  • কোনো stall detection নেই — সত্যিই আটকালে কেউ জানে না
  • storage pre-flight শুধু input size ধরে (temp + gallery = 2× দরকার)
