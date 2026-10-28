/*
 * pass_source_files.c — Store full source file content in the graph database.
 *
 * Runs as a post-extraction pre-dump pass. Iterates every discovered file,
 * reads its content, and persists an LZ4-compressed blob into the source_files
 * table. External consumers (CodeGraph, etc.) can then retrieve verbatim
 * source bodies via cbm_store_get_source_file() without re-reading the
 * filesystem.
 *
 * Deliberately a best-effort pass: a single unreadable file is skipped;
 * only a hard DB error aborts the pass.
 */
#include "foundation/constants.h"

#include "pipeline/pipeline.h"
#include "pipeline/pipeline_internal.h"
#include "store/store.h"
#include "foundation/log.h"
#include "foundation/platform.h"
#include "foundation/compat_fs.h"

#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

/* Maximum file size to store (2 MB). Files over this limit are silently
 * skipped — source-body retrieval is for AI context, not archive. */
enum { SOURCE_MAX_SIZE = 2 * 1024 * 1024 };

static const char *itoa_buf(int v) {
    static _Thread_local char buf[4][16];
    static _Thread_local int idx = 0;
    idx = (idx + 1) & 3;
    snprintf(buf[idx], sizeof(buf[idx]), "%d", v);
    return buf[idx];
}

int cbm_pipeline_pass_source_files(cbm_pipeline_ctx_t *ctx, const cbm_file_info_t *files,
                                    int file_count) {
    if (!ctx || !ctx->repo_path || !files || file_count <= 0 || !ctx->pipeline) {
        return 0;
    }

    const char *db_path = cbm_pipeline_db_path(ctx->pipeline);
    if (!db_path) {
        return 0;
    }

    cbm_store_t *store = cbm_store_open_path(db_path);
    if (!store) {
        cbm_log_warn("source_files.err", "reason", "open_failed");
        return -1;
    }

    int stored = 0;
    int skipped_size = 0;
    int skipped_read = 0;
    size_t repo_len = strlen(ctx->repo_path);

    for (int i = 0; i < file_count; i++) {
        if (cbm_pipeline_check_cancel(ctx)) {
            break;
        }
        if (files[i].size > SOURCE_MAX_SIZE) {
            skipped_size++;
            continue;
        }

        /* Build full path: repo_path + "/" + rel_path */
        const char *rel = files[i].path;
        size_t rel_len = strlen(rel);
        size_t full_len = repo_len + 1 + rel_len;
        char *full_path = malloc(full_len + 1);
        if (!full_path) {
            continue;
        }
        memcpy(full_path, ctx->repo_path, repo_len);
        full_path[repo_len] = '/';
        memcpy(full_path + repo_len + 1, rel, rel_len + 1);

        /* Read file content */
        FILE *fp = fopen(full_path, "rb");
        if (!fp) {
            free(full_path);
            skipped_read++;
            continue;
        }
        fseek(fp, 0, SEEK_END);
        long fsize = ftell(fp);
        if (fsize <= 0 || fsize > SOURCE_MAX_SIZE) {
            fclose(fp);
            free(full_path);
            skipped_size++;
            continue;
        }
        rewind(fp);
        char *buf = malloc((size_t)fsize);
        if (!buf) {
            fclose(fp);
            free(full_path);
            continue;
        }
        size_t nread = fread(buf, 1, (size_t)fsize, fp);
        fclose(fp);

        if (nread != (size_t)fsize) {
            free(buf);
            free(full_path);
            skipped_read++;
            continue;
        }

        /* Upsert into store (LZ4-compressed internally) */
        int rc = cbm_store_upsert_source_file(store, ctx->project_name, rel, buf, (int)nread);
        free(buf);
        free(full_path);

        if (rc == CBM_STORE_OK) {
            stored++;
        }
    }

    cbm_store_close(store);
    cbm_log_info("source_files.done", "stored", itoa_buf(stored), "skipped_size",
                 itoa_buf(skipped_size), "skipped_read", itoa_buf(skipped_read));
    return 0;
}
