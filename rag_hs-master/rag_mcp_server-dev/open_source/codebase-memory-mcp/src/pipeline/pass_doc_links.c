/*
 * pass_doc_links.c — Document-to-code bidirectional linkage.
 *
 * Scans Document nodes in the graph buffer, reads their source text,
 * extracts code-symbol references (backtick identifiers, code blocks,
 * file paths, section headings), resolves them to graph nodes, and
 * creates DOCUMENTS_CODE / DOCUMENTS_IP edges.
 *
 * Reverse DOCUMENTED_BY edges are also emitted so code→doc queries
 * work natively.
 *
 * Runs as a pre-dump pass after chip_nodes.
 */
#include "foundation/constants.h"

#include "pipeline/pipeline.h"
#include "pipeline/pipeline_internal.h"
#include "graph_buffer/graph_buffer.h"
#include "store/store.h"
#include "foundation/log.h"
#include "foundation/platform.h"
#include "foundation/compat_fs.h"

#include <ctype.h>
#include <dirent.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <strings.h>
#include <sys/stat.h>

enum { DL_MAX_RESULTS = 12, DL_PROPS_CAP = 512, DL_QN_CAP = 512, DL_LINE_CAP = 2048,
       DL_CANDIDATE_CAP = 4000, DL_ID_CAP = 128, DL_DOC_CAP = 256 };

/* ── Helpers ──────────────────────────────────────────────────────── */

static const char *itoa_buf(int v) {
    static _Thread_local char b[4][16];
    static _Thread_local int idx = 0;
    idx = (idx + 1) & 3;
    snprintf(b[idx], sizeof(b[idx]), "%d", v);
    return b[idx];
}

static bool is_ident_start(char c) {
    return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_';
}

static bool is_ident_body(char c) {
    return is_ident_start(c) || (c >= '0' && c <= '9');
}

/* Extract a code identifier token: Foo, Foo.bar, Foo.bar.baz */
static int extract_ident(const char *line, int start, char *out, int out_sz) {
    int i = start;
    while (i < DL_LINE_CAP && line[i] && is_ident_body(line[i])) i++;
    /* Allow dot-separated qualified names */
    while (line[i] == '.' && i + 1 < DL_LINE_CAP && line[i + 1] && is_ident_start(line[i + 1])) {
        i++;
        while (i < DL_LINE_CAP && line[i] && is_ident_body(line[i])) i++;
    }
    int len = i - start;
    if (len < 2 || len >= out_sz) return 0;
    memcpy(out, line + start, (size_t)len);
    out[len] = '\0';
    return len;
}

/* ── Candidate extraction ─────────────────────────────────────────── */

typedef struct {
    char name[DL_QN_CAP]; /* symbol name or file path */
    int line;             /* 1-based line number */
    bool is_file_path;    /* true if this looks like a file path */
} dl_candidate_t;

/* Stopwords: common words that aren't code symbols */
static bool is_stopword(const char *w) {
    static const char *sw[] = {"the", "and", "for", "with", "from", "this",
        "that", "not", "are", "can", "use", "all", "its", "has", "set",
        "get", "new", "see", "also", "note", "each", "one", "two", NULL};
    for (int i = 0; sw[i]; i++)
        if (strcmp(w, sw[i]) == 0) return true;
    return false;
}

/* True when 's' contains a file-like pattern: slash + extension */
static bool looks_like_file_path(const char *s) {
    const char *slash = strrchr(s, '/');
    const char *dot = strrchr(s, '.');
    return slash != NULL || (dot != NULL && dot > s && dot[1] != '\0');
}

/* Check if name is a known IP block type keyword */
static bool is_ip_type_keyword(const char *s) {
    static const char *types[] = {"UART","SPI","I2C","DMA","GPIO","PWM","ADC","DAC",
        "RTC","WDT","USB","PCIe","MIPI","SDIO","Ethernet","CAN","LIN","I2S",
        "NPU","GPU","DSP","VPU","ISP","CRYPTO","TRNG","OTP","eFuse","PLL","PMU",
        "UART-v","SPI-v","DMA-v","NPU-v","GPU-v",NULL};
    for (int i = 0; types[i]; i++)
        if (strstr(s, types[i])) return true;
    return false;
}

/* Extract code candidates from a document body */
static int extract_candidates(const char *source, dl_candidate_t *out, int max_out) {
    int count = 0;
    bool in_fence = false;
    char buf[DL_LINE_CAP];
    int line_no = 0;
    const char *p = source;

    while (*p && count < max_out) {
        /* Read one line */
        int len = 0;
        while (*p && *p != '\n' && len < DL_LINE_CAP - 1) buf[len++] = *p++;
        buf[len] = '\0';
        if (*p == '\n') p++;
        line_no++;

        /* Skip empty lines */
        if (len == 0) continue;

        /* Fence toggle */
        if (strncmp(buf, "```", 3) == 0) {
            in_fence = !in_fence;
            continue;
        }

        /* In code fence: treat entire line as candidate source */
        if (in_fence) {
            for (int i = 0; i < len && count < max_out; i++) {
                /* Accept identifier at line start, or after a token-boundary char
                 * commonly seen in code: whitespace, opening/closing brackets,
                 * punctuation, assignment, comparison. Extracted so `(td_void)memset_s(...)`
                 * yields `memset_s`, not just `td_void`. */
                if (is_ident_start(buf[i]) && (i == 0 ||
                    buf[i - 1] == ' '  || buf[i - 1] == '\t' ||
                    buf[i - 1] == '('  || buf[i - 1] == ')' ||
                    buf[i - 1] == '['  || buf[i - 1] == ']' ||
                    buf[i - 1] == '{'  || buf[i - 1] == '}' ||
                    buf[i - 1] == '<'  || buf[i - 1] == '>' ||
                    buf[i - 1] == '.'  || buf[i - 1] == ',' ||
                    buf[i - 1] == ';'  || buf[i - 1] == ':' ||
                    buf[i - 1] == '='  || buf[i - 1] == '!' ||
                    buf[i - 1] == '&'  || buf[i - 1] == '|' ||
                    buf[i - 1] == '+'  || buf[i - 1] == '-' ||
                    buf[i - 1] == '*'  || buf[i - 1] == '/' ||
                    buf[i - 1] == '%'  || buf[i - 1] == '~' ||
                    buf[i - 1] == '?'  || buf[i - 1] == '\'')) {
                    char ident[DL_QN_CAP];
                    int ilen = extract_ident(buf, i, ident, sizeof(ident));
                    if (ilen > 0 && !is_stopword(ident)) {
                        out[count].line = line_no;
                        out[count].is_file_path = looks_like_file_path(ident);
                        strncpy(out[count].name, ident, DL_QN_CAP - 1);
                        count++;
                        i += ilen - 1;
                    }
                }
            }
            continue;
        }

        /* Heading: ## Some Title → check if title contains symbol-ish text */
        if (buf[0] == '#') {
            int start = 0;
            while (start < len && (buf[start] == '#' || buf[start] == ' ')) start++;
            if (start < len && is_ident_start(buf[start])) {
                char ident[DL_QN_CAP];
                int ilen = extract_ident(buf, start, ident, sizeof(ident));
                if (ilen > 0 && !is_stopword(ident)) {
                    out[count].line = line_no;
                    out[count].is_file_path = false;
                    strncpy(out[count].name, ident, DL_QN_CAP - 1);
                    count++;
                }
            }
            continue;
        }

        /* Inline code: `symbol` or `Class.method` */
        for (int i = 0; i < len && count < max_out; i++) {
            if (buf[i] == '`') {
                int j = i + 1;
                while (j < len && buf[j] != '`') j++;
                if (j > i + 1 && j < len) {
                    /* Extract content between backticks */
                    int clen = j - i - 1;
                    if (clen < DL_QN_CAP - 1) {
                        char code[DL_QN_CAP];
                        memcpy(code, buf + i + 1, (size_t)clen);
                        code[clen] = '\0';
                        /* Only accept identifier-looking code (not full commands) */
                        if (is_ident_start(code[0]) && clen >= 2 && !strchr(code, ' ')) {
                            out[count].line = line_no;
                            out[count].is_file_path = looks_like_file_path(code);
                            strncpy(out[count].name, code, DL_QN_CAP - 1);
                            count++;
                        }
                    }
                }
                i = j;
            }
            /* File path in plain text: src/auth/login.c */
            else if (buf[i] == '/' && i > 0 && is_ident_body(buf[i - 1])) {
                int start = i - 1;
                while (start > 0 && (is_ident_body(buf[start - 1]) || buf[start - 1] == '/'))
                    start--;
                char path[DL_QN_CAP];
                int plen = i - start + 1;
                for (int k = i + 1; k < len && plen < DL_QN_CAP - 1; k++) {
                    if (is_ident_body(buf[k]) || buf[k] == '/' || buf[k] == '.' || buf[k] == '-' ||
                        buf[k] == '_') {
                        plen = k - start + 1;
                    } else {
                        break;
                    }
                }
                if (plen > 3 && plen < DL_QN_CAP - 1 && strchr(buf + start, '.')) {
                    memcpy(path, buf + start, (size_t)plen);
                    path[plen] = '\0';
                    if (looks_like_file_path(path)) {
                        out[count].line = line_no;
                        out[count].is_file_path = true;
                        strncpy(out[count].name, path, DL_QN_CAP - 1);
                        count++;
                    }
                }
            }
        }
    }
    return count;
}

/* ── Node resolution ──────────────────────────────────────────────── */

/* Find a code node matching the candidate name. Returns NULL if ambiguous (>cap). */
static const cbm_gbuf_node_t *resolve_candidate(cbm_gbuf_t *gbuf, const char *name,
                                                 const char *file_path_hint) {
    /* Try exact qualified_name match first */
    const cbm_gbuf_node_t *node = cbm_gbuf_find_by_qn(gbuf, name);
    if (node) return node;

    /* Try name match */
    const cbm_gbuf_node_t **matches = NULL;
    int match_count = 0;
    if (cbm_gbuf_find_by_name(gbuf, name, &matches, &match_count) != 0 || !matches)
        return NULL;

    /* Filter to code-like nodes only */
    const cbm_gbuf_node_t *best = NULL;
    int code_count = 0;
    for (int i = 0; i < match_count; i++) {
        const char *label = matches[i]->label;
        if (!label) continue;
        if (strcmp(label, "Function") == 0 || strcmp(label, "Method") == 0 ||
            strcmp(label, "Class") == 0 || strcmp(label, "Interface") == 0 ||
            strcmp(label, "Struct") == 0 || strcmp(label, "Module") == 0 ||
            strcmp(label, "File") == 0 || strcmp(label, "Enum") == 0) {
            code_count++;
            best = matches[i];
            /* Prefer same-directory matches when hint is available */
            if (file_path_hint && matches[i]->file_path) {
                const char *bs = strrchr(file_path_hint, '/');
                const char *ms = strrchr(matches[i]->file_path, '/');
                if (bs && ms) {
                    size_t bl = (size_t)(bs - file_path_hint);
                    size_t ml = (size_t)(ms - matches[i]->file_path);
                    if (bl == ml && strncmp(file_path_hint, matches[i]->file_path, bl) == 0)
                        return matches[i];
                }
            }
        }
    }

    /* Return unique match, or best same-dir match, or NULL if too ambiguous */
    if (code_count == 1) return best;
    if (code_count > DL_MAX_RESULTS) return NULL; /* too ambiguous */
    return best; /* return first match */
}

/* ── Auto document discovery ──────────────────────────────────────── */

enum { DL_AUTO_COLLECT_CAP = 4000, DL_AUTO_DOC_CAP = 1000,
       DL_AUTO_PATH_MAX = 1024 };

typedef struct {
    char rel[DL_AUTO_PATH_MAX];
} dl_autodoc_t;

static bool dl_skip_dir(const char *d) {
    return strcmp(d, ".git") == 0 || strcmp(d, ".codegraph") == 0 ||
           strcmp(d, "node_modules") == 0;
}

static void dl_collect_md(const char *root, const char *rel,
                          dl_autodoc_t *out, int *n) {
    if (*n >= DL_AUTO_COLLECT_CAP) return;
    char dir[DL_AUTO_PATH_MAX * 2];
    if (rel[0]) snprintf(dir, sizeof(dir), "%s/%s", root, rel);
    else snprintf(dir, sizeof(dir), "%s", root);
    DIR *d = opendir(dir);
    if (!d) return;
    struct dirent *ent;
    while ((ent = readdir(d)) != NULL && *n < DL_AUTO_COLLECT_CAP) {
        if (ent->d_name[0] == '.' &&
            (ent->d_name[1] == '\0' ||
             (ent->d_name[1] == '.' && ent->d_name[2] == '\0')))
            continue;
        size_t len = strlen(ent->d_name);
        bool is_dir;
#ifdef DT_DIR
        if (ent->d_type == DT_DIR) {
            is_dir = true;
        } else if (ent->d_type == DT_UNKNOWN) {
#endif
            char probe[DL_AUTO_PATH_MAX * 2];
            if (rel[0]) snprintf(probe, sizeof(probe), "%s/%s/%s", root, rel, ent->d_name);
            else snprintf(probe, sizeof(probe), "%s/%s", root, ent->d_name);
            struct stat st;
            is_dir = stat(probe, &st) == 0 && S_ISDIR(st.st_mode);
#ifdef DT_DIR
        } else {
            is_dir = false;
        }
#endif
        if (is_dir) {
            if (!dl_skip_dir(ent->d_name)) {
                char child[DL_AUTO_PATH_MAX];
                if (rel[0]) snprintf(child, sizeof(child), "%s/%s", rel, ent->d_name);
                else snprintf(child, sizeof(child), "%s", ent->d_name);
                if (strlen(child) + 1 < sizeof(out[*n].rel))
                    dl_collect_md(root, child, out, n);
            }
            continue;
        }
        if (len > 3 && strcasecmp(ent->d_name + len - 3, ".md") == 0) {
            if (rel[0]) snprintf(out[*n].rel, DL_AUTO_PATH_MAX, "%s/%s", rel, ent->d_name);
            else snprintf(out[*n].rel, DL_AUTO_PATH_MAX, "%s", ent->d_name);
            (*n)++;
        }
    }
    closedir(d);
}

/* 优先级：README/SKILL 类高信号文档优先，其次浅路径（顶层文档通常更重要） */
static int dl_autodoc_score(const char *rel) {
    const char *base = strrchr(rel, '/');
    base = base ? base + 1 : rel;
    if (strncasecmp(base, "readme", 6) == 0) return 0;
    if (strncasecmp(base, "skill", 5) == 0) return 1;
    int depth = 0;
    for (const char *p = rel; *p; p++)
        if (*p == '/') depth++;
    return 2 + depth;
}

static int dl_autodoc_cmp(const void *a, const void *b) {
    const dl_autodoc_t *x = (const dl_autodoc_t *)a;
    const dl_autodoc_t *y = (const dl_autodoc_t *)b;
    int sx = dl_autodoc_score(x->rel), sy = dl_autodoc_score(y->rel);
    if (sx != sy) return sx < sy ? -1 : 1;
    return strcmp(x->rel, y->rel);
}

static int dl_autodiscover_docs(cbm_pipeline_ctx_t *ctx, cbm_gbuf_t *gbuf) {
    dl_autodoc_t *found = calloc(DL_AUTO_COLLECT_CAP, sizeof(*found));
    if (!found) return 0;
    int n = 0;
    dl_collect_md(ctx->repo_path, "", found, &n);
    qsort(found, (size_t)n, sizeof(*found), dl_autodoc_cmp);
    int emitted = 0;
    for (int i = 0; i < n && emitted < DL_AUTO_DOC_CAP; i++) {
        const char *rel = found[i].rel;
        const char *base = strrchr(rel, '/');
        base = base ? base + 1 : rel;
        char qn[DL_AUTO_PATH_MAX + 16];
        snprintf(qn, sizeof(qn), "__doc__%s", rel);
        if (cbm_gbuf_upsert_node(gbuf, "Document", base, qn, rel, 0, 0,
                                 "{\"doc_type\":\"markdown\",\"source\":\"auto\"}") > 0)
            emitted++;
    }
    cbm_log_info("doc_links.autodiscover", "found", itoa_buf(n),
                 "emitted", itoa_buf(emitted));
    free(found);
    return emitted;
}

/* ── Edge emission ────────────────────────────────────────────────── */

static int dl_emit(cbm_gbuf_t *gbuf, int64_t src, int64_t tgt, const char *type,
                   const char *ref_name, int line) {
    if (src <= 0 || tgt <= 0 || src == tgt) return 0;
    char props[DL_PROPS_CAP];
    snprintf(props, sizeof(props),
             "{\"provenance\":\"doc-link\",\"ref_name\":\"%s\",\"line\":%d}",
             ref_name ? ref_name : "", line);
    return cbm_gbuf_insert_edge(gbuf, src, tgt, type, props) > 0 ? 1 : 0;
}

/* ── Entry point ──────────────────────────────────────────────────── */

int cbm_pipeline_pass_doc_links(cbm_pipeline_ctx_t *ctx) {
    if (!ctx || !ctx->gbuf || !ctx->repo_path) return 0;
    cbm_gbuf_t *gbuf = ctx->gbuf;

    /* Find all Document nodes via label search */
    const cbm_gbuf_node_t **doc_nodes = NULL;
    int doc_count = 0;
    cbm_gbuf_find_by_label(gbuf, "Document", &doc_nodes, &doc_count);
    if (doc_count == 0) {
        /* 无 .chip.yaml 清单声明文档时自动发现 *.md：建索引即生成文档↔代码
         * 映射，不依赖人工维护清单。清单声明过的项目不走自动发现（manifest
         * 优先），QN 用相对路径去重（清单版是 __doc__<basename>）。
         * 注意 find_by_label 无命中时 out 为 NULL，不能据此早退。 */
        if (dl_autodiscover_docs(ctx, gbuf) == 0) return 0;
        cbm_gbuf_find_by_label(gbuf, "Document", &doc_nodes, &doc_count);
    }
    if (!doc_nodes || doc_count == 0) return 0;

    /* Also collect IPBlock nodes for IP-specific matching */
    const cbm_gbuf_node_t **ip_nodes = NULL;
    int ip_count = 0;
    cbm_gbuf_find_by_label(gbuf, "IPBlock", &ip_nodes, &ip_count);

    int total_edges = 0;
    size_t repo_len = strlen(ctx->repo_path);

    for (int di = 0; di < doc_count; di++) {
        const cbm_gbuf_node_t *doc = doc_nodes[di];
        if (!doc || !doc->file_path) continue;

        /* Read document source */
        size_t fp_len = strlen(doc->file_path);
        char *full_path = malloc(repo_len + 1 + fp_len + 1);
        if (!full_path) continue;
        memcpy(full_path, ctx->repo_path, repo_len);
        full_path[repo_len] = '/';
        memcpy(full_path + repo_len + 1, doc->file_path, fp_len + 1);

        FILE *fp = fopen(full_path, "rb");
        free(full_path);
        if (!fp) continue;

        fseek(fp, 0, SEEK_END);
        long sz = ftell(fp);
        if (sz <= 0 || sz > 2 * 1024 * 1024) { fclose(fp); continue; }
        rewind(fp);
        char *source = malloc((size_t)sz + 1);
        if (!source) { fclose(fp); continue; }
        size_t nread = fread(source, 1, (size_t)sz, fp);
        fclose(fp);
        if (nread != (size_t)sz) { free(source); continue; }
        source[sz] = '\0';

        /* Extract candidates */
        dl_candidate_t candidates[DL_CANDIDATE_CAP];
        int nc = extract_candidates(source, candidates, DL_CANDIDATE_CAP);
        free(source);

        /* Track seen to avoid duplicate edges */
        int64_t seen_ids[DL_ID_CAP];
        int seen_count = 0;

        for (int ci = 0; ci < nc && total_edges < DL_CANDIDATE_CAP * 2; ci++) {
            dl_candidate_t *cand = &candidates[ci];

            /* Try IP block match first (for DOCUMENTS_IP edges) */
            if (is_ip_type_keyword(cand->name) && ip_nodes) {
                for (int ipi = 0; ipi < ip_count; ipi++) {
                    if (strcmp(ip_nodes[ipi]->name, cand->name) == 0) {
                        total_edges += dl_emit(gbuf, doc->id, ip_nodes[ipi]->id,
                                                "DOCUMENTS_IP", cand->name, cand->line);
                        break;
                    }
                }
            }

            /* Resolve to code node */
            const cbm_gbuf_node_t *target = resolve_candidate(gbuf, cand->name, doc->file_path);
            if (!target) continue;

            /* Skip if target is the document itself */
            if (target->id == doc->id) continue;

            /* Deduplicate */
            bool dup = false;
            for (int si = 0; si < seen_count; si++) {
                if (seen_ids[si] == target->id) { dup = true; break; }
            }
            if (dup) continue;
            if (seen_count < DL_ID_CAP) seen_ids[seen_count++] = target->id;

            /* Create DOCUMENTS_CODE edge */
            total_edges += dl_emit(gbuf, doc->id, target->id, "DOCUMENTS_CODE",
                                    cand->name, cand->line);

            /* Create reverse DOCUMENTED_BY edge */
            total_edges += dl_emit(gbuf, target->id, doc->id, "DOCUMENTED_BY",
                                    doc->file_path, cand->line);
        }
    }

    if (total_edges > 0)
        cbm_log_info("doc_links.done", "edges", itoa_buf(total_edges), "docs",
                     itoa_buf(doc_count));
    return total_edges;
}
