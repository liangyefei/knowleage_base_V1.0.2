/*
 * pass_version_tracking.c — Version, firmware-image, and lifecycle nodes.
 *
 * Discovers git tags, creates Version nodes, links them to chips via
 * VERSION_FOR edges, and chains VERSION_PARENT edges.
 *
 * Also creates FirmwareImage nodes from chip build_configs that
 * reference output artifacts.
 *
 * Runs as a pre-dump pass.
 */
#include "foundation/constants.h"

#include "pipeline/pipeline.h"
#include "pipeline/pipeline_internal.h"
#include "graph_buffer/graph_buffer.h"
#include "foundation/log.h"
#include "foundation/platform.h"
#include "foundation/compat.h"

#include <ctype.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

enum { VT_QN_CAP = 512, VT_PROPS_CAP = 1024, VT_TAG_CAP = 64, VT_MAX_TAGS = 128 };

static const char *itoa_buf(int v) {
    static _Thread_local char b[4][16];
    static _Thread_local int idx = 0;
    idx = (idx + 1) & 3;
    snprintf(b[idx], sizeof(b[idx]), "%d", v);
    return b[idx];
}

/* ── Git tag discovery ───────────────────────────────────────────── */

/* Call git tag and collect sorted version tags */
static int discover_tags(const char *repo_path, char tags[][VT_TAG_CAP], int max_tags) {
    char cmd[1024];
    snprintf(cmd, sizeof(cmd),
             "cd %s && git tag --sort=version:refname 2>/dev/null", repo_path);

    FILE *fp = popen(cmd, "r");
    if (!fp) return 0;

    int count = 0;
    char line[VT_TAG_CAP];
    while (fgets(line, sizeof(line), fp) && count < max_tags) {
        size_t len = strlen(line);
        while (len > 0 && (line[len - 1] == '\n' || line[len - 1] == '\r'))
            line[--len] = '\0';
        if (len > 0 && len < VT_TAG_CAP) {
            memcpy(tags[count], line, len + 1);
            count++;
        }
    }
    pclose(fp);
    return count;
}

/* Get commit SHA for a tag */
static int get_tag_sha(const char *repo_path, const char *tag, char *sha, int sha_sz) {
    char cmd[1024];
    snprintf(cmd, sizeof(cmd),
             "cd %s && git rev-list -n 1 %s 2>/dev/null", repo_path, tag);
    FILE *fp = popen(cmd, "r");
    if (!fp) return -1;
    if (!fgets(sha, sha_sz, fp)) { pclose(fp); return -1; }
    size_t len = strlen(sha);
    while (len > 0 && (sha[len - 1] == '\n' || sha[len - 1] == '\r'))
        sha[--len] = '\0';
    pclose(fp);
    return len > 0 ? 0 : -1;
}

/* Get timestamp for a tag */
static int get_tag_ts(const char *repo_path, const char *tag, char *ts, int ts_sz) {
    char cmd[1024];
    snprintf(cmd, sizeof(cmd),
             "cd %s && git log -1 --format=%%aI %s 2>/dev/null", repo_path, tag);
    FILE *fp = popen(cmd, "r");
    if (!fp) return -1;
    if (!fgets(ts, ts_sz, fp)) { pclose(fp); return -1; }
    size_t len = strlen(ts);
    while (len > 0 && (ts[len - 1] == '\n' || ts[len - 1] == '\r'))
        ts[--len] = '\0';
    pclose(fp);
    return len > 0 ? 0 : -1;
}

/* ── Entry point ──────────────────────────────────────────────────── */

int cbm_pipeline_pass_version_tracking(cbm_pipeline_ctx_t *ctx) {
    if (!ctx || !ctx->gbuf || !ctx->repo_path) return 0;
    cbm_gbuf_t *gbuf = ctx->gbuf;

    char tags[VT_MAX_TAGS][VT_TAG_CAP];
    int tag_count = discover_tags(ctx->repo_path, tags, VT_MAX_TAGS);
    if (tag_count < 2) return 0; /* need at least 2 versions for meaningful tracking */

    /* Find all Chip nodes to link versions to */
    const cbm_gbuf_node_t **chip_nodes = NULL;
    int chip_count = 0;
    cbm_gbuf_find_by_label(gbuf, "Chip", &chip_nodes, &chip_count);

    int created = 0;
    int64_t prev_id = 0;

    for (int ti = 0; ti < tag_count; ti++) {
        char sha[41] = "";
        char ts[32] = "";
        get_tag_sha(ctx->repo_path, tags[ti], sha, sizeof(sha));
        get_tag_ts(ctx->repo_path, tags[ti], ts, sizeof(ts));

        /* Create Version node */
        char qn[VT_QN_CAP], props[VT_PROPS_CAP];
        snprintf(qn, sizeof(qn), "version.%s", tags[ti]);
        snprintf(props, sizeof(props),
                 "{\"tag\":\"%s\",\"commit\":\"%s\",\"timestamp\":\"%s\"}",
                 tags[ti], sha, ts);

        int64_t vid = cbm_gbuf_upsert_node(gbuf, "Version", tags[ti], qn, "", 0, 0, props);
        if (vid <= 0) continue;
        created++;

        /* VERSION_PARENT edge */
        if (prev_id > 0)
            cbm_gbuf_insert_edge(gbuf, vid, prev_id, "VERSION_PARENT", "{}");
        prev_id = vid;

        /* Link to all chips (VERSION_FOR) */
        for (int ci = 0; ci < chip_count; ci++)
            cbm_gbuf_insert_edge(gbuf, vid, chip_nodes[ci]->id, "VERSION_FOR", "{}");
    }

    /* Find BuildConfig nodes and create FirmwareImage nodes */
    const cbm_gbuf_node_t **cfg_nodes = NULL;
    int cfg_count = 0;
    cbm_gbuf_find_by_label(gbuf, "BuildConfig", &cfg_nodes, &cfg_count);

    for (int ci = 0; ci < cfg_count; ci++) {
        const cbm_gbuf_node_t *cfg = cfg_nodes[ci];
        if (!cfg || !cfg->name) continue;

        /* Create a FirmwareImage for each build config */
        char fw_qn[VT_QN_CAP], fw_name[VT_QN_CAP], fw_props[VT_PROPS_CAP];
        snprintf(fw_name, sizeof(fw_name), "fw-%s", cfg->name);
        snprintf(fw_qn, sizeof(fw_qn), "firmware.%s", cfg->name);
        snprintf(fw_props, sizeof(fw_props),
                 "{\"build_config\":\"%s\"}", cfg->name);
        int64_t fw_id = cbm_gbuf_upsert_node(gbuf, "FirmwareImage", fw_name, fw_qn,
                                              "", 0, 0, fw_props);
        if (fw_id > 0) {
            cbm_gbuf_insert_edge(gbuf, cfg->id, fw_id, "PRODUCES", "{}");
            /* Also link to the latest Version node */
            if (prev_id > 0)
                cbm_gbuf_insert_edge(gbuf, fw_id, prev_id, "FW_VERSION", "{}");
            created++;
        }
    }

    if (created > 0)
        cbm_log_info("version.done", "versions", itoa_buf(tag_count), "nodes",
                     itoa_buf(created));
    return created;
}
