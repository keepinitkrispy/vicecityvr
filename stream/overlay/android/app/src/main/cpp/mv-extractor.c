// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
// Vice City VR: a second, software H.264 decoder that runs only to export motion vectors.
// The hardware decoder still makes the picture. This one skips the inverse transform and the deblocking filter
// (the costly parts of rebuilding pixels) and runs on a low-priority thread with its own copy of each frame.
#define _GNU_SOURCE
#include "mv-extractor.h"

#include <libavcodec/avcodec.h>
#include <libavutil/motion_vector.h>

#include <math.h>
#include <pthread.h>
#include <stdlib.h>
#include <string.h>
#include <sys/resource.h>
#include <sys/syscall.h>
#include <time.h>
#include <unistd.h>

#define QUEUE 8

typedef struct { uint8_t *data; size_t size; } Packet;

struct MvExtractor {
  AVCodecContext *ctx;
  AVPacket *pkt;
  AVFrame *frame;
  pthread_t thread;
  pthread_mutex_t lock;
  pthread_cond_t cond;
  Packet q[QUEUE];
  int head, count, stop;
  // stats since last read, guarded by lock
  double ms_sum, ms_max, cover_sum, motion_sum;
  uint32_t frames, dropped;
  // latest field, guarded by lock
  float field[MV_GRID_W * MV_GRID_H * 3];
  uint32_t field_frame;
};

static double thread_ms(void) {
  struct timespec t;
  clock_gettime(CLOCK_THREAD_CPUTIME_ID, &t);
  return t.tv_sec * 1e3 + t.tv_nsec / 1e6;
}

static void analyse(MvExtractor *mv, const AVFrame *f) {
  float acc[MV_GRID_W * MV_GRID_H * 3];
  memset(acc, 0, sizeof acc);
  const AVFrameSideData *sd = av_frame_get_side_data(f, AV_FRAME_DATA_MOTION_VECTORS);
  double covered = 0, motion = 0, motion_w = 0;
  if (sd) {
    const AVMotionVector *v = (const AVMotionVector *)sd->data;
    size_t n = sd->size / sizeof(*v);
    for (size_t i = 0; i < n; i++) {
      if (v[i].source > 0) continue;                          // only references to past frames
      float dx = (float)(v[i].dst_x - v[i].src_x), dy = (float)(v[i].dst_y - v[i].src_y);   // how content moved
      float area = (float)v[i].w * (float)v[i].h;
      covered += area;
      motion += sqrtf(dx * dx + dy * dy) * area; motion_w += area;
      int cx = v[i].dst_x * MV_GRID_W / (f->width > 0 ? f->width : 1920);
      int cy = v[i].dst_y * MV_GRID_H / (f->height > 0 ? f->height : 1080);
      if (cx < 0 || cy < 0 || cx >= MV_GRID_W || cy >= MV_GRID_H) continue;
      float *c = &acc[(cy * MV_GRID_W + cx) * 3];
      c[0] += dx * area; c[1] += dy * area; c[2] += area;
    }
  }
  for (int i = 0; i < MV_GRID_W * MV_GRID_H; i++)
    if (acc[i * 3 + 2] > 0) { acc[i * 3] /= acc[i * 3 + 2]; acc[i * 3 + 1] /= acc[i * 3 + 2]; }
  double area = (double)f->width * f->height;
  pthread_mutex_lock(&mv->lock);
  memcpy(mv->field, acc, sizeof acc);
  mv->field_frame++;
  mv->cover_sum += area > 0 ? (covered > area ? 1.0 : covered / area) : 0;
  mv->motion_sum += motion_w > 0 ? motion / motion_w : 0;
  pthread_mutex_unlock(&mv->lock);
}

static void *worker(void *arg) {
  MvExtractor *mv = arg;
  setpriority(PRIO_PROCESS, (id_t)syscall(SYS_gettid), 10);   // below the game stream and the renderer
  for (;;) {
    pthread_mutex_lock(&mv->lock);
    while (!mv->count && !mv->stop) pthread_cond_wait(&mv->cond, &mv->lock);
    if (mv->stop) { pthread_mutex_unlock(&mv->lock); break; }
    Packet p = mv->q[mv->head];
    mv->head = (mv->head + 1) % QUEUE; mv->count--;
    pthread_mutex_unlock(&mv->lock);

    double t0 = thread_ms();
    mv->pkt->data = p.data; mv->pkt->size = (int)p.size;
    int frames = 0;
    if (avcodec_send_packet(mv->ctx, mv->pkt) >= 0)
      while (avcodec_receive_frame(mv->ctx, mv->frame) >= 0) { analyse(mv, mv->frame); frames++; av_frame_unref(mv->frame); }
    double ms = thread_ms() - t0;
    free(p.data);
    if (frames) {
      pthread_mutex_lock(&mv->lock);
      mv->ms_sum += ms; if (ms > mv->ms_max) mv->ms_max = ms; mv->frames += frames;
      pthread_mutex_unlock(&mv->lock);
    }
  }
  return NULL;
}

MvExtractor *mv_extractor_new(void) {
  const AVCodec *codec = avcodec_find_decoder(AV_CODEC_ID_H264);
  if (!codec) return NULL;
  MvExtractor *mv = calloc(1, sizeof *mv);
  if (!mv) return NULL;
  mv->ctx = avcodec_alloc_context3(codec);
  mv->pkt = av_packet_alloc();
  mv->frame = av_frame_alloc();
  if (!mv->ctx || !mv->pkt || !mv->frame) goto fail;
  mv->ctx->thread_count = 1;                        // one core, no frame-threading delay
  mv->ctx->flags |= AV_CODEC_FLAG_LOW_DELAY;
  mv->ctx->flags2 |= AV_CODEC_FLAG2_FAST;
  mv->ctx->export_side_data |= AV_CODEC_EXPORT_DATA_MVS;
  mv->ctx->skip_loop_filter = AVDISCARD_ALL;        // pixels are not needed, only where blocks came from
  mv->ctx->skip_idct = AVDISCARD_ALL;
  if (avcodec_open2(mv->ctx, codec, NULL) < 0) goto fail;
  pthread_mutex_init(&mv->lock, NULL);
  pthread_cond_init(&mv->cond, NULL);
  if (pthread_create(&mv->thread, NULL, worker, mv) != 0) goto fail;
  return mv;
fail:
  avcodec_free_context(&mv->ctx); av_packet_free(&mv->pkt); av_frame_free(&mv->frame); free(mv);
  return NULL;
}

void mv_extractor_free(MvExtractor *mv) {
  if (!mv) return;
  pthread_mutex_lock(&mv->lock); mv->stop = 1; pthread_cond_signal(&mv->cond); pthread_mutex_unlock(&mv->lock);
  pthread_join(mv->thread, NULL);
  for (int i = 0; i < mv->count; i++) free(mv->q[(mv->head + i) % QUEUE].data);
  avcodec_free_context(&mv->ctx); av_packet_free(&mv->pkt); av_frame_free(&mv->frame);
  pthread_mutex_destroy(&mv->lock); pthread_cond_destroy(&mv->cond);
  free(mv);
}

void mv_extractor_feed(MvExtractor *mv, const uint8_t *buf, size_t size) {
  if (!mv || !buf || !size) return;
  uint8_t *copy = malloc(size + AV_INPUT_BUFFER_PADDING_SIZE);
  if (!copy) return;
  memcpy(copy, buf, size); memset(copy + size, 0, AV_INPUT_BUFFER_PADDING_SIZE);
  pthread_mutex_lock(&mv->lock);
  if (mv->count == QUEUE) {                         // behind: drop the oldest rather than add latency
    free(mv->q[mv->head].data); mv->head = (mv->head + 1) % QUEUE; mv->count--; mv->dropped++;
  }
  mv->q[(mv->head + mv->count) % QUEUE] = (Packet){copy, size}; mv->count++;
  pthread_cond_signal(&mv->cond);
  pthread_mutex_unlock(&mv->lock);
}

void mv_extractor_stats(MvExtractor *mv, float out[6]) {
  memset(out, 0, 6 * sizeof(float));
  if (!mv) return;
  pthread_mutex_lock(&mv->lock);
  if (mv->frames) {
    out[0] = (float)(mv->ms_sum / mv->frames); out[1] = (float)mv->ms_max;
    out[2] = (float)(mv->cover_sum / mv->frames); out[3] = (float)(mv->motion_sum / mv->frames);
  }
  out[4] = (float)mv->dropped; out[5] = (float)mv->frames;
  mv->ms_sum = mv->ms_max = mv->cover_sum = mv->motion_sum = 0; mv->frames = mv->dropped = 0;
  pthread_mutex_unlock(&mv->lock);
}

uint32_t mv_extractor_field(MvExtractor *mv, float *dst) {
  if (!mv) return 0;
  pthread_mutex_lock(&mv->lock);
  memcpy(dst, mv->field, sizeof mv->field);
  uint32_t n = mv->field_frame;
  pthread_mutex_unlock(&mv->lock);
  return n;
}
