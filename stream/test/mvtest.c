// Feeds an Annex-B H.264 file to the extractor one access unit at a time (split at access unit delimiters),
// the way chiaki hands frames over, then prints what the extractor measured.
#include "../overlay/android/app/src/main/cpp/mv-extractor.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

int main(int argc, char **argv) {
  if (argc < 2) { fprintf(stderr, "usage: mvtest file.h264 [fps]\n"); return 1; }
  FILE *f = fopen(argv[1], "rb"); if (!f) { perror(argv[1]); return 1; }
  fseek(f, 0, SEEK_END); long n = ftell(f); fseek(f, 0, SEEK_SET);
  uint8_t *b = malloc(n); if (fread(b, 1, n, f) != (size_t)n) return 1; fclose(f);
  int fps = argc > 2 ? atoi(argv[2]) : 0;
  MvExtractor *mv = mv_extractor_new(); if (!mv) { fprintf(stderr, "no extractor\n"); return 1; }
  long start = 0; int units = 0;
  for (long i = 4; i + 4 <= n; i++) {
    int sc = b[i] == 0 && b[i + 1] == 0 && b[i + 2] == 1 ? 3 : (b[i] == 0 && b[i + 1] == 0 && b[i + 2] == 0 && b[i + 3] == 1 ? 4 : 0);
    if (sc && (b[i + sc] & 0x1f) == 9 && i > start) {              // next access unit delimiter
      mv_extractor_feed(mv, b + start, i - start); units++; start = i;
      if (fps) usleep(1000000 / fps); else usleep(2000);
    }
    if (sc) i += sc - 1;                                            // a 4-byte start code also contains a 3-byte one
  }
  mv_extractor_feed(mv, b + start, n - start); units++;
  usleep(300000);
  float s[6]; mv_extractor_stats(mv, s);
  static float fld[MV_GRID_W * MV_GRID_H * 3]; mv_extractor_field(mv, fld);
  double dx = 0, dy = 0, w = 0; int cells = 0;
  for (int i = 0; i < MV_GRID_W * MV_GRID_H; i++) if (fld[i * 3 + 2] > 0) { dx += fld[i * 3]; dy += fld[i * 3 + 1]; w++; cells++; }
  printf("units=%d frames=%.0f dropped=%.0f cpu_ms_avg=%.2f cpu_ms_max=%.2f coverage=%.2f mean_motion_px=%.2f last_field_mean=(%.2f,%.2f) cells=%d\n",
         units, s[5], s[4], s[0], s[1], s[2], s[3], w ? dx / w : 0, w ? dy / w : 0, cells);
  mv_extractor_free(mv); free(b);
  return 0;
}
