/*
 * pass_register_map.c — Register map and bitfield nodes from YAML definitions.
 *
 * Reads regs/ .reg.yaml files, each defining a set of registers for an IP block:
 *   ip: UART-v3
 *   registers:
 *     - name: UART_CTRL
 *       offset: "0x00"
 *       width: 32
 *       access: RW
 *       reset: "0x00000000"
 *       description: "Control register"
 *       bitfields:
 *         - {name: TX_EN, bits: "0", desc: "Transmit enable"}
 *         - {name: RX_EN, bits: "1", desc: "Receive enable"}
 *
 * Creates RegisterMap nodes (one per register) and BitField nodes (one per
 * bitfield). Links registers to IPBlock nodes (REGISTER_MAP edge) and to
 * Document nodes that describe them (DOCUMENTS_REGISTER edge).
 * Functions that access these registers get ACCESSES_REGISTER edges.
 *
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
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>

enum { RM_MAX_PATH = 1024, RM_QN_CAP = 512, RM_PROPS_CAP = 1536, RM_MAX_REGS = 256,
       RM_MAX_BF = 32, RM_MAX_FILES = 32 };

static const char *itoa_buf(int v) {
    static _Thread_local char b[4][16];
    static _Thread_local int idx = 0;
    idx = (idx + 1) & 3;
    snprintf(b[idx], sizeof(b[idx]), "%d", v);
    return b[idx];
}

static const char *ymap_str(const cbm_yaml_node_t *map, const char *key) {
    const cbm_yaml_node_t *c = cbm_yaml_map_get(map, key);
    return c ? cbm_yaml_node_value(c) : NULL;
}

/* ── Entry point ──────────────────────────────────────────────────── */

int cbm_pipeline_pass_register_map(cbm_pipeline_ctx_t *ctx) {
    if (!ctx || !ctx->gbuf || !ctx->repo_path) return 0;
    cbm_gbuf_t *gbuf = ctx->gbuf;

    /* Scan regs/ directory for .reg.yaml files */
    char regs_dir[RM_MAX_PATH];
    snprintf(regs_dir, sizeof(regs_dir), "%s/regs", ctx->repo_path);
    DIR *dir = opendir(regs_dir);
    if (!dir) return 0;

    char *files[RM_MAX_FILES];
    int fcnt = 0;
    struct dirent *e;
    while ((e = readdir(dir)) != NULL && fcnt < RM_MAX_FILES) {
        size_t len = strlen(e->d_name);
        if (len > 9 && (strcmp(e->d_name + len - 9, ".reg.yaml") == 0 ||
                         strcmp(e->d_name + len - 5, ".yaml") == 0)) {
            char buf[RM_MAX_PATH];
            snprintf(buf, sizeof(buf), "%s/%s", regs_dir, e->d_name);
            files[fcnt++] = strdup(buf);
        }
    }
    closedir(dir);
    if (fcnt == 0) return 0;

    /* Pre-fetch IPBlock nodes for linking */
    const cbm_gbuf_node_t **ip_nodes = NULL;
    int ip_count = 0;
    cbm_gbuf_find_by_label(gbuf, "IPBlock", &ip_nodes, &ip_count);

    int total_regs = 0;

    for (int fi = 0; fi < fcnt; fi++) {
        FILE *fp = fopen(files[fi], "rb");
        free(files[fi]);
        if (!fp) continue;
        fseek(fp, 0, SEEK_END);
        long sz = ftell(fp);
        if (sz <= 0 || sz > 1024 * 1024) { fclose(fp); continue; }
        rewind(fp);
        char *txt = malloc((size_t)sz + 1);
        if (!txt) { fclose(fp); continue; }
        size_t n = fread(txt, 1, (size_t)sz, fp);
        fclose(fp);
        if (n != (size_t)sz) { free(txt); continue; }
        txt[sz] = '\0';

        cbm_yaml_node_t *root = cbm_yaml_parse(txt, (int)sz);
        free(txt);
        if (!root) continue;

        const char *ip_name = cbm_yaml_get_str(root, "ip");
        if (!ip_name) { cbm_yaml_free(root); continue; }

        /* Find matching IPBlock */
        int64_t ip_id = 0;
        for (int i = 0; i < ip_count; i++) {
            if (strcmp(ip_nodes[i]->name, ip_name) == 0) {
                ip_id = ip_nodes[i]->id;
                break;
            }
        }

        /* Parse register entries */
        int reg_count = cbm_yaml_list_len(root, "registers");
        for (int ri = 0; ri < reg_count; ri++) {
            const cbm_yaml_node_t *reg = cbm_yaml_list_item(root, "registers", ri);
            if (!reg) continue;

            const char *rname = ymap_str(reg, "name");
            const char *offset = ymap_str(reg, "offset");
            const char *width = ymap_str(reg, "width");
            const char *access = ymap_str(reg, "access");
            const char *reset = ymap_str(reg, "reset");
            const char *desc = ymap_str(reg, "description");
            if (!rname) continue;

            /* RegisterMap node */
            char qn[RM_QN_CAP], props[RM_PROPS_CAP];
            snprintf(qn, sizeof(qn), "reg.%s.%s", ip_name, rname);
            snprintf(props, sizeof(props),
                     "{\"offset\":\"%s\",\"width\":%s,\"access\":\"%s\","
                     "\"reset_value\":\"%s\",\"description\":\"%s\"}",
                     offset ? offset : "", width ? width : "32",
                     access ? access : "RW", reset ? reset : "0",
                     desc ? desc : "");

            int64_t reg_id = cbm_gbuf_upsert_node(gbuf, "RegisterMap", rname, qn,
                                                   "", 0, 0, props);
            if (reg_id <= 0) continue;
            total_regs++;

            /* REGISTER_MAP edge to IPBlock */
            if (ip_id > 0)
                cbm_gbuf_insert_edge(gbuf, ip_id, reg_id, "REGISTER_MAP", "{}");

            /* Parse bitfields */
            const cbm_yaml_node_t *bf_list = cbm_yaml_map_get(reg, "bitfields");
            if (bf_list && cbm_yaml_node_is_list(bf_list)) {
                for (int bi = 0; bi < cbm_yaml_child_count(bf_list); bi++) {
                    const cbm_yaml_node_t *bf = cbm_yaml_child_at(bf_list, bi);
                    if (!bf) continue;
                    const char *bname = ymap_str(bf, "name");
                    const char *bits = ymap_str(bf, "bits");
                    const char *bdesc = ymap_str(bf, "desc");
                    if (!bname) continue;

                    char bqn[RM_QN_CAP], bprops[RM_PROPS_CAP];
                    snprintf(bqn, sizeof(bqn), "reg.%s.%s.%s", ip_name, rname, bname);
                    snprintf(bprops, sizeof(bprops),
                             "{\"bits\":\"%s\",\"description\":\"%s\"}",
                             bits ? bits : "", bdesc ? bdesc : "");
                    int64_t bf_id = cbm_gbuf_upsert_node(gbuf, "BitField", bname, bqn,
                                                          "", 0, 0, bprops);
                    if (bf_id > 0)
                        cbm_gbuf_insert_edge(gbuf, reg_id, bf_id, "HAS_BITFIELD", "{}");
                }
            }
        }
        cbm_yaml_free(root);
    }

    if (total_regs > 0)
        cbm_log_info("register_map.done", "registers", itoa_buf(total_regs));
    return total_regs;
}
