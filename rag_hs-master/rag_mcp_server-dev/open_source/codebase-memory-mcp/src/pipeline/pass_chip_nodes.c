/*
 * pass_chip_nodes.c — Chip knowledge base entities from .chip.yaml spec files.
 *
 * Scans chips/ directory for .chip.yaml files, creates Chip/ChipFamily/
 * IPInstance/Board/BuildConfig/Document nodes with interconnecting edges.
 * Runs as a pre-dump pass.
 */
#include "foundation/constants.h"

#include "pipeline/pipeline.h"
#include "pipeline/pipeline_internal.h"
#include "graph_buffer/graph_buffer.h"
#include "foundation/yaml.h"
#include "foundation/log.h"
#include "foundation/platform.h"
#include "foundation/compat_fs.h"

#include <dirent.h>
#include <ctype.h>
#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>

enum { CS_MAX_PATH = 1024, CS_MAX_QN = 512, CS_PROPS_CAP = 2048, CS_CHIP_CAP = 64 };

/* ── Helpers ──────────────────────────────────────────────────────── */

static const char *itoa_buf(int v) {
    static _Thread_local char b[4][16];
    static _Thread_local int idx = 0;
    idx = (idx + 1) & 3;
    snprintf(b[idx], sizeof(b[idx]), "%d", v);
    return b[idx];
}

static bool file_exists(const char *path) {
    struct stat st;
    return stat(path, &st) == 0 && S_ISREG(st.st_mode);
}

static char *read_file(const char *path, int *out_len) {
    FILE *fp = fopen(path, "rb");
    if (!fp) return NULL;
    fseek(fp, 0, SEEK_END);
    long sz = ftell(fp);
    if (sz <= 0 || sz > 2 * 1024 * 1024) { fclose(fp); return NULL; }
    rewind(fp);
    char *buf = malloc((size_t)sz + 1);
    if (!buf) { fclose(fp); return NULL; }
    size_t n = fread(buf, 1, (size_t)sz, fp);
    fclose(fp);
    if (n != (size_t)sz) { free(buf); return NULL; }
    buf[sz] = '\0';
    if (out_len) *out_len = (int)sz;
    return buf;
}

static const char *yamls(const cbm_yaml_node_t *root, const char *path) {
    return cbm_yaml_get_str(root, path);
}

static const char *ymap_str(const cbm_yaml_node_t *map, const char *key) {
    const cbm_yaml_node_t *child = cbm_yaml_map_get(map, key);
    return child ? cbm_yaml_node_value(child) : NULL;
}

/* ── Node/edge emission ───────────────────────────────────────────── */

static int64_t emit(cbm_gbuf_t *gbuf, const char *label, const char *name,
                    const char *qn, const char *file_path, const char *props) {
    return cbm_gbuf_upsert_node(gbuf, label, name, qn, file_path ? file_path : "",
                                0, 0, props ? props : "{}");
}

static int link(cbm_gbuf_t *gbuf, int64_t src, int64_t tgt, const char *type) {
    if (src <= 0 || tgt <= 0 || src == tgt) return 0;
    return cbm_gbuf_insert_edge(gbuf, src, tgt, type, "{}") > 0 ? 1 : 0;
}

/* ── Family node ──────────────────────────────────────────────────── */

static int64_t ensure_family(cbm_gbuf_t *gbuf, const cbm_yaml_node_t *root) {
    const char *family = yamls(root, "chip.family");
    const char *arch = yamls(root, "chip.architecture");
    const char *node = yamls(root, "chip.process_node");
    if (!family) return 0;

    char qn[CS_MAX_QN], name[CS_MAX_QN], props[CS_PROPS_CAP];
    const char *mfr = yamls(root, "chip.manufacturer");
    if (mfr && mfr[0]) snprintf(name, sizeof(name), "%s %s", mfr, family);
    else strncpy(name, family, sizeof(name) - 1);

    int pos = 0;
    for (const char *s = family; *s && pos < (int)sizeof(qn) - 1; s++)
        qn[pos++] = (*s == ' ') ? '-' : (char)tolower((unsigned char)*s);
    qn[pos] = '\0';

    snprintf(props, sizeof(props),
             "{\"architecture\":\"%s\",\"process_node\":\"%s\"}",
             arch ? arch : "", node ? node : "");
    return emit(gbuf, "ChipFamily", name, qn, "", props);
}

/* ── IP Blocks ────────────────────────────────────────────────────── */

static int parse_ips(cbm_gbuf_t *gbuf, int64_t chip_id, const cbm_yaml_node_t *root) {
    int n = cbm_yaml_list_len(root, "chip.ip_blocks");
    int added = 0;
    for (int i = 0; i < n; i++) {
        const cbm_yaml_node_t *item = cbm_yaml_list_item(root, "chip.ip_blocks", i);
        if (!item) continue;
        const char *ip_name = ymap_str(item, "name");
        if (!ip_name) continue;
        const char *ip_type = ymap_str(item, "type");

        char qn[CS_MAX_QN], props[CS_PROPS_CAP];
        snprintf(qn, sizeof(qn), "ip.%s", ip_name);
        snprintf(props, sizeof(props), "{\"type\":\"%s\"}", ip_type ? ip_type : "");
        int64_t ip_id = emit(gbuf, "IPBlock", ip_name, qn, "", props);
        added += link(gbuf, chip_id, ip_id, "INSTANTIATES_IP");

        /* Instances */
        const cbm_yaml_node_t *insts = cbm_yaml_map_get(item, "instances");
        if (!insts || !cbm_yaml_node_is_list(insts)) continue;
        for (int j = 0; j < cbm_yaml_child_count(insts); j++) {
            const cbm_yaml_node_t *inst = cbm_yaml_child_at(insts, j);
            if (!inst) continue;
            const char *iname = ymap_str(inst, "name");
            if (!iname) continue;
            const char *base = ymap_str(inst, "base_addr");
            const char *irq = ymap_str(inst, "irq");
            char iqn[CS_MAX_QN];
            snprintf(iqn, sizeof(iqn), "ip.%s.%s", ip_name, iname);
            snprintf(props, sizeof(props), "{\"base_addr\":\"%s\",\"irq\":\"%s\"}",
                     base ? base : "", irq ? irq : "");
            int64_t inst_id = emit(gbuf, "IPInstance", iname, iqn, "", props);
            link(gbuf, ip_id, inst_id, "HAS_INSTANCE");
        }
    }
    return added;
}

/* ── Revisions ────────────────────────────────────────────────────── */

static int parse_revs(cbm_gbuf_t *gbuf, int64_t chip_id, const cbm_yaml_node_t *root) {
    int n = cbm_yaml_list_len(root, "chip.revisions");
    int added = 0;
    const char *chip_name = yamls(root, "chip.name");
    if (!chip_name) chip_name = "unknown";
    int64_t prev_id = 0;

    for (int i = 0; i < n; i++) {
        const cbm_yaml_node_t *item = cbm_yaml_list_item(root, "chip.revisions", i);
        if (!item) continue;
        const char *step = ymap_str(item, "stepping");
        if (!step) continue;
        const char *date = ymap_str(item, "release_date");
        const char *changes = ymap_str(item, "changes");

        char qn[CS_MAX_QN], name[CS_MAX_QN], props[CS_PROPS_CAP];
        snprintf(name, sizeof(name), "%s-%s", chip_name, step);
        snprintf(qn, sizeof(qn), "chip.%s.%s", chip_name, step);
        snprintf(props, sizeof(props),
                 "{\"stepping\":\"%s\",\"release_date\":\"%s\",\"changes\":\"%s\"}",
                 step, date ? date : "", changes ? changes : "");
        int64_t rev_id = emit(gbuf, "ChipRevision", name, qn, "", props);
        added += link(gbuf, chip_id, rev_id, "CHIP_REVISION");
        if (prev_id > 0) added += link(gbuf, rev_id, prev_id, "PREDECESSOR_REV");
        prev_id = rev_id;
    }
    return added;
}

/* ── Boards ───────────────────────────────────────────────────────── */

static int parse_boards(cbm_gbuf_t *gbuf, int64_t chip_id, const cbm_yaml_node_t *root) {
    int n = cbm_yaml_list_len(root, "chip.boards");
    int added = 0;
    for (int i = 0; i < n; i++) {
        const cbm_yaml_node_t *item = cbm_yaml_list_item(root, "chip.boards", i);
        if (!item) continue;
        const char *bname = ymap_str(item, "name");
        if (!bname) continue;
        const char *btype = ymap_str(item, "type");
        char qn[CS_MAX_QN], props[CS_PROPS_CAP];
        snprintf(qn, sizeof(qn), "board.%s", bname);
        snprintf(props, sizeof(props), "{\"board_type\":\"%s\"}",
                 btype ? btype : "eval");
        int64_t bid = emit(gbuf, "Board", bname, qn, "", props);
        added += link(gbuf, bid, chip_id, "USES_CHIP");
    }
    return added;
}

/* ── Documents ────────────────────────────────────────────────────── */

static int parse_docs(cbm_gbuf_t *gbuf, int64_t chip_id, const cbm_yaml_node_t *root) {
    int n = cbm_yaml_list_len(root, "chip.documents");
    int added = 0;
    for (int i = 0; i < n; i++) {
        const cbm_yaml_node_t *item = cbm_yaml_list_item(root, "chip.documents", i);
        if (!item) continue;
        const char *path = ymap_str(item, "path");
        if (!path) continue;
        const char *dtype = ymap_str(item, "type");
        const char *dver = ymap_str(item, "version");
        const char *bn = strrchr(path, '/');
        bn = bn ? bn + 1 : path;
        char qn[CS_MAX_QN], props[CS_PROPS_CAP];
        snprintf(qn, sizeof(qn), "__doc__%s", bn);
        snprintf(props, sizeof(props), "{\"doc_type\":\"%s\",\"version\":\"%s\"}",
                 dtype ? dtype : "unknown", dver ? dver : "");
        int64_t did = emit(gbuf, "Document", bn, qn, path, props);
        added += link(gbuf, did, chip_id, "DOCUMENTS_CHIP");
    }
    return added;
}

/* ── Build configs ────────────────────────────────────────────────── */

static int parse_cfgs(cbm_gbuf_t *gbuf, int64_t chip_id, const cbm_yaml_node_t *root) {
    int n = cbm_yaml_list_len(root, "chip.build_configs");
    int added = 0;
    for (int i = 0; i < n; i++) {
        const cbm_yaml_node_t *item = cbm_yaml_list_item(root, "chip.build_configs", i);
        if (!item) continue;
        const char *cname = ymap_str(item, "name");
        if (!cname) continue;
        const char *compiler = ymap_str(item, "compiler");
        const char *cflags = ymap_str(item, "cflags");
        char qn[CS_MAX_QN], props[CS_PROPS_CAP];
        snprintf(qn, sizeof(qn), "build.%s", cname);
        snprintf(props, sizeof(props), "{\"compiler\":\"%s\",\"cflags\":\"%s\"}",
                 compiler ? compiler : "", cflags ? cflags : "");
        int64_t cid = emit(gbuf, "BuildConfig", cname, qn, "", props);
        added += link(gbuf, cid, chip_id, "BUILDS_FOR");
    }
    return added;
}

/* ── Entry point ──────────────────────────────────────────────────── */

int cbm_pipeline_pass_chip_nodes(cbm_pipeline_ctx_t *ctx) {
    if (!ctx || !ctx->gbuf || !ctx->repo_path) return 0;
    cbm_gbuf_t *gbuf = ctx->gbuf;

    char chips_dir[CS_MAX_PATH];
    snprintf(chips_dir, sizeof(chips_dir), "%s/chips", ctx->repo_path);

    /* Collect .chip.yaml files */
    char *files[CS_CHIP_CAP];
    int fcnt = 0;

    DIR *dir = opendir(chips_dir);
    if (dir) {
        struct dirent *e;
        while ((e = readdir(dir)) != NULL && fcnt < CS_CHIP_CAP) {
            size_t len = strlen(e->d_name);
            if (len > 10 && strcmp(e->d_name + len - 10, ".chip.yaml") == 0) {
                char buf[CS_MAX_PATH];
                snprintf(buf, sizeof(buf), "%s/%s", chips_dir, e->d_name);
                files[fcnt++] = strdup(buf);
            }
        }
        closedir(dir);
    } else {
        /* Try single chip.yaml at repo root */
        char buf[CS_MAX_PATH];
        snprintf(buf, sizeof(buf), "%s/chip.yaml", ctx->repo_path);
        if (file_exists(buf)) files[fcnt++] = strdup(buf);
    }
    if (fcnt == 0) return 0;

    int chips = 0, edges = 0;
    for (int i = 0; i < fcnt; i++) {
        int flen = 0;
        char *txt = read_file(files[i], &flen);
        free(files[i]);
        if (!txt) continue;

        cbm_yaml_node_t *root = cbm_yaml_parse(txt, flen);
        free(txt);
        if (!root) continue;

        const char *cn = yamls(root, "chip.name");
        if (!cn) { cbm_yaml_free(root); continue; }
        const char *fu = yamls(root, "chip.full_name");
        const char *ar = yamls(root, "chip.architecture");
        const char *pn = yamls(root, "chip.process_node");
        const char *lc = yamls(root, "chip.lifecycle");
        const char *ds = yamls(root, "chip.description");
        const char *co = yamls(root, "chip.cores");
        const char *fr = yamls(root, "chip.max_frequency_mhz");
        const char *pk = yamls(root, "chip.package");
        const char *td = yamls(root, "chip.tdp_watts");

        char props[CS_PROPS_CAP];
        snprintf(props, sizeof(props),
                 "{\"full_name\":\"%s\",\"architecture\":\"%s\","
                 "\"process_node\":\"%s\",\"lifecycle\":\"%s\","
                 "\"description\":\"%s\",\"cores\":\"%s\","
                 "\"max_frequency_mhz\":\"%s\",\"package\":\"%s\","
                 "\"tdp_watts\":\"%s\"}",
                 fu ? fu : cn, ar ? ar : "", pn ? pn : "",
                 lc ? lc : "unknown", ds ? ds : "",
                 co ? co : "0", fr ? fr : "0",
                 pk ? pk : "", td ? td : "0");

        char qn[CS_MAX_QN];
        snprintf(qn, sizeof(qn), "chip.%s", cn);
        int64_t cid = emit(gbuf, "Chip", cn, qn, "", props);
        if (cid <= 0) { cbm_yaml_free(root); continue; }
        chips++;

        int64_t fid = ensure_family(gbuf, root);
        if (fid > 0) edges += link(gbuf, cid, fid, "BELONGS_TO_FAMILY");

        edges += parse_ips(gbuf, cid, root);
        edges += parse_revs(gbuf, cid, root);
        edges += parse_boards(gbuf, cid, root);
        edges += parse_docs(gbuf, cid, root);
        edges += parse_cfgs(gbuf, cid, root);

        cbm_yaml_free(root);
    }

    if (chips > 0)
        cbm_log_info("chip.done", "chips", itoa_buf(chips), "edges", itoa_buf(edges));
    return chips;
}
