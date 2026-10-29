# RNNoise (vendored)

Source: https://github.com/xiph/rnnoise, tag `v0.1`, commit `cdf196b1e9de2f8ff1003328ebf9a4316477429d`
(fetched 2026-10-29). License: BSD-3-Clause, see `COPYING` (Mozilla, Xiph.Org Foundation and others).

Copied unchanged: `src/*.c`, `src/*.h`, `include/rnnoise.h`. Left out: the training tools
(`rnn_train.py`, `compile.sh`, `training/`). Built by `../CMakeLists.txt` into `libvaultchat_rnnoise.so`
together with `../rnnoise_jni.c`. Runs entirely on the phone; no audio leaves the device because of it.
