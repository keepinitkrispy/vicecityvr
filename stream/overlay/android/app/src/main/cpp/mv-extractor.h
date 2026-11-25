// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
// Vice City VR: reads the PS5 encoder's own motion vectors out of the H.264 stream, next to the hardware decoder.
#ifndef VCVR_MV_EXTRACTOR_H
#define VCVR_MV_EXTRACTOR_H

#include <stddef.h>
#include <stdint.h>

#define MV_GRID_W 60   // 32 px cells on a 1920x1080 stream
#define MV_GRID_H 34

typedef struct MvExtractor MvExtractor;

/** NULL if the stream is not H.264 or FFmpeg is missing. */
MvExtractor *mv_extractor_new(void);
void mv_extractor_free(MvExtractor *mv);
/** Copies the access unit and returns at once; never blocks the hardware decoder. */
void mv_extractor_feed(MvExtractor *mv, const uint8_t *buf, size_t size);
/**
 * out[0] CPU ms per frame (average since last call), out[1] max, out[2] share of the picture with vectors (0..1),
 * out[3] mean motion in px per frame, out[4] frames dropped since last call, out[5] frames processed since last call
 */
void mv_extractor_stats(MvExtractor *mv, float out[6]);
/** Latest grid, MV_GRID_W x MV_GRID_H cells of (dx, dy, weight), px per frame, row 0 = top. Returns frame number. */
uint32_t mv_extractor_field(MvExtractor *mv, float *dst);

#endif
