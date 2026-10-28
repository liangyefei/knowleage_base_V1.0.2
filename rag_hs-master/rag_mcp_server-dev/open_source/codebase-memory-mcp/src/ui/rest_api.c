/*
 * rest_api.c — REST API layer over MCP tools.
 *
 * Routes (under /api/v1/):
 *   GET    /projects                         → tools/list_projects
 *   POST   /projects/{name}/index            → tools/index_repository
 *   GET    /projects/{name}/status           → tools/index_status
 *   GET    /projects/{name}/coverage         → tools/check_index_coverage
 *   DELETE /projects/{name}                  → tools/delete_project
 *   GET    /projects/{name}/search           → tools/search_graph
 *   POST   /projects/{name}/cypher           → tools/query_graph
 *   GET    /projects/{name}/trace            → tools/trace_path
 *   GET    /projects/{name}/code             → tools/get_code_snippet
 *   GET    /projects/{name}/architecture     → tools/get_architecture
 *   GET    /projects/{name}/schema           → tools/get_graph_schema
 *
 * Each handler builds the MCP tool arguments JSON, invokes cbm_mcp_handle_tool,
 * then wraps the tool's text content in the REST envelope.
 */
#include "ui/rest_api.h"
#include "ui/httpd.h"
#include "mcp/mcp.h"

#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

/* CORS + JSON Content-Type header for REST responses (self-contained so this
 * module does not depend on http_server.c's static singleton). */
static const char *REST_JSON_HDR =
    "Access-Control-Allow-Origin: *\r\n"
    "Access-Control-Allow-Methods: GET, POST, DELETE, OPTIONS\r\n"
    "Access-Control-Allow-Headers: Content-Type\r\n"
    "Content-Type: application/json\r\n";

/* ── Small helpers ────────────────────────────────────────────────── */

/* URL-decode a query value in place (decodes %XX and +). Returns the decoded
 * length. Output is NUL-terminated. */
static int url_decode(const char *in, int in_len, char *out, int out_sz) {
    int o = 0;
    for (int i = 0; i < in_len && o < out_sz - 1; i++) {
        if (in[i] == '+') {
            out[o++] = ' ';
        } else if (in[i] == '%' && i + 2 < in_len) {
            char hex[3] = {in[i + 1], in[i + 2], '\0'};
            out[o++] = (char)strtol(hex, NULL, 16);
            i += 2;
        } else {
            out[o++] = in[i];
        }
    }
    out[o] = '\0';
    return o;
}

/* Find a query parameter value by key. Returns a heap-allocated decoded
 * string (caller frees), or NULL if absent. */
static char *query_get(const char *query, const char *key) {
    if (!query || !key) return NULL;
    size_t klen = strlen(key);
    const char *p = query;
    while (*p) {
        const char *eq = strchr(p, '=');
        const char *amp = strchr(p, '&');
        int seg_len = eq ? (int)(eq - p) : (amp ? (int)(amp - p) : (int)strlen(p));
        if ((size_t)seg_len == klen && strncmp(p, key, klen) == 0 && eq) {
            int val_len = amp ? (int)(amp - eq - 1) : (int)strlen(eq + 1);
            char *out = malloc((size_t)val_len + 1);
            if (!out) return NULL;
            url_decode(eq + 1, val_len, out, (int)val_len + 1);
            return out;
        }
        if (!amp) break;
        p = amp + 1;
    }
    return NULL;
}

/* Extract the {name} segment from a path like /api/v1/projects/{name}/search.
 * Writes the segment to out. Returns true on success. */
static bool path_project_name(const char *path, char *out, int out_sz) {
    /* path = /api/v1/projects/<name>[/<rest>] */
    const char *prefix = "/api/v1/projects/";
    size_t plen = strlen(prefix);
    if (strncmp(path, prefix, plen) != 0) return false;
    const char *start = path + plen;
    const char *slash = strchr(start, '/');
    int len = slash ? (int)(slash - start) : (int)strlen(start);
    if (len <= 0 || len >= out_sz) return false;
    memcpy(out, start, (size_t)len);
    out[len] = '\0';
    return true;
}

/* What comes after /api/v1/projects/{name}? Returns "" for the project root,
 * "/search" etc. NULL if path is the project root itself or malformed. */
static const char *path_after_project(const char *path) {
    const char *prefix = "/api/v1/projects/";
    size_t plen = strlen(prefix);
    if (strncmp(path, prefix, plen) != 0) return NULL;
    const char *start = path + plen;
    const char *slash = strchr(start, '/');
    return slash ? slash : NULL; /* NULL = no sub-path (project root) */
}

/* ── MCP tool invocation ─────────────────────────────────────────── */

/* Call an MCP tool by name with a JSON arguments string. Returns the tool's
 * text content (heap-allocated, caller frees) and sets *is_error. */
static char *call_tool(cbm_mcp_server_t *mcp, const char *tool_name,
                       const char *args_json, bool *is_error) {
    /* Build the tools/call request envelope */
    char *req = NULL;
    int n = asprintf(&req,
                     "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\","
                     "\"params\":{\"name\":\"%s\",\"arguments\":%s}}",
                     tool_name, args_json ? args_json : "{}");
    if (n < 0 || !req) return NULL;

    char *resp = cbm_mcp_server_handle(mcp, req);
    free(req);
    if (!resp) return NULL;

    /* Parse the response to extract content text + isError */
    char *text = NULL;
    *is_error = false;

    /* Minimal JSON extraction: find "isError":true and "text":"..." */
    if (strstr(resp, "\"isError\":true")) *is_error = true;

    /* Extract text from content array */
    const char *needle = "\"text\":\"";
    const char *t = strstr(resp, needle);
    if (t) {
        t += strlen(needle);
        /* Find unescaped closing quote */
        size_t cap = strlen(t) + 1;
        char *buf = malloc(cap);
        if (buf) {
            int o = 0;
            while (*t && o < (int)cap - 1) {
                if (*t == '\\' && t[1]) {
                    char c = t[1];
                    if (c == 'n') buf[o++] = '\n';
                    else if (c == 't') buf[o++] = '\t';
                    else if (c == 'r') buf[o++] = '\r';
                    else if (c == '"') buf[o++] = '"';
                    else if (c == '\\') buf[o++] = '\\';
                    else buf[o++] = c;
                    t += 2;
                } else if (*t == '"') {
                    break;
                } else {
                    buf[o++] = *t++;
                }
            }
            buf[o] = '\0';
            text = buf;
        }
    } else {
        text = strdup("");
    }

    free(resp);
    return text;
}

/* Reply with a REST envelope. HTTP status semantics:
 *   - 200: the request was handled (tool ran), success OR tool-level error.
 *          The body's "ok" field distinguishes them — REST consumers branch on
 *          ok, not on HTTP status, because a tool reporting "project not found"
 *          is a legitimate application-level result, not a transport failure.
 *   - 500: the REST layer itself failed to invoke the tool (protocol fault). */
static void reply_envelope(cbm_http_conn_t *c, bool ok, const char *text,
                           const char *err_code, const char *err_msg) {
    /* We need to JSON-escape text. Build via a simple escaper. */
    char *escaped = NULL;
    if (text) {
        size_t cap = strlen(text) * 6 + 1;
        escaped = malloc(cap);
        if (escaped) {
            size_t o = 0;
            for (const char *s = text; *s && o < cap - 8; s++) {
                unsigned char ch = (unsigned char)*s;
                if (ch == '\n') { escaped[o++] = '\\'; escaped[o++] = 'n'; }
                else if (ch == '\r') { escaped[o++] = '\\'; escaped[o++] = 'r'; }
                else if (ch == '\t') { escaped[o++] = '\\'; escaped[o++] = 't'; }
                else if (ch == '"') { escaped[o++] = '\\'; escaped[o++] = '"'; }
                else if (ch == '\\') { escaped[o++] = '\\'; escaped[o++] = '\\'; }
                else if (ch < 0x20) {
                    o += (size_t)snprintf(escaped + o, cap - o, "\\u%04x", ch);
                } else {
                    escaped[o++] = (char)ch;
                }
            }
            escaped[o] = '\0';
        }
    }

    if (ok) {
        cbm_http_replyf(c, 200, REST_JSON_HDR,
                        "{\"ok\":true,\"data\":{\"content\":\"%s\"},\"error\":null}",
                        escaped ? escaped : "");
    } else {
        cbm_http_replyf(c, 200, REST_JSON_HDR,
                        "{\"ok\":false,\"data\":null,\"error\":{\"code\":\"%s\","
                        "\"message\":\"%s\"}}",
                        err_code ? err_code : "ERROR",
                        err_msg ? err_msg : "");
    }
    free(escaped);
}

/* Convenience: call a tool and reply with the envelope. A tool-level error
 * (isError) still yields HTTP 200 with ok:false in the body; only a failure
 * to invoke the tool at all is a 500. */
static void handle_tool(cbm_http_conn_t *c, cbm_mcp_server_t *mcp,
                        const char *tool, const char *args_json) {
    bool is_error = false;
    char *text = call_tool(mcp, tool, args_json, &is_error);
    if (!text) {
        /* Protocol fault — could not run the tool. */
        cbm_http_replyf(c, 500, REST_JSON_HDR,
                        "{\"ok\":false,\"data\":null,\"error\":{\"code\":\"INTERNAL\","
                        "\"message\":\"tool invocation failed\"}}");
        return;
    }
    reply_envelope(c, !is_error, text, "TOOL_ERROR", text);
    free(text);
}

/* ── Individual route handlers ────────────────────────────────────── */

static void rest_list_projects(cbm_http_conn_t *c, cbm_mcp_server_t *mcp) {
    handle_tool(c, mcp, "list_projects", "{}");
}

static void rest_delete_project(cbm_http_conn_t *c, cbm_mcp_server_t *mcp,
                                const char *name) {
    char *args = NULL;
    if (asprintf(&args, "{\"project\":\"%s\"}", name) < 0 || !args) {
        reply_envelope(c, false, NULL, "INTERNAL", "oom");
        return;
    }
    handle_tool(c, mcp, "delete_project", args);
    free(args);
}

static void rest_index(cbm_http_conn_t *c, const cbm_http_req_t *req,
                       cbm_mcp_server_t *mcp, const char *name) {
    /* Body expected: {"repo_path": "...", "mode": "fast"} */
    char *repo_path = NULL;
    char *mode = NULL;
    if (req->body && req->body_len > 0) {
        const char *p = strstr(req->body, "\"repo_path\"");
        if (p) {
            p = strchr(p, ':');
            if (p) {
                p = strchr(p, '"');
                if (p++) {
                    const char *e = strchr(p, '"');
                    if (e) {
                        repo_path = malloc((size_t)(e - p) + 1);
                        if (repo_path) {
                            memcpy(repo_path, p, (size_t)(e - p));
                            repo_path[e - p] = '\0';
                        }
                    }
                }
            }
        }
        const char *m = strstr(req->body, "\"mode\"");
        if (m) {
            m = strchr(m, ':');
            if (m) {
                m = strchr(m, '"');
                if (m++) {
                    const char *e = strchr(m, '"');
                    if (e) {
                        mode = malloc((size_t)(e - m) + 1);
                        if (mode) {
                            memcpy(mode, m, (size_t)(e - m));
                            mode[e - m] = '\0';
                        }
                    }
                }
            }
        }
    }

    char *args = NULL;
    asprintf(&args, "{\"repo_path\":\"%s\",\"name\":\"%s\",\"mode\":\"%s\"}",
             repo_path ? repo_path : "", name, mode ? mode : "fast");
    free(repo_path);
    free(mode);
    if (!args) { reply_envelope(c, false, NULL, "INTERNAL", "oom"); return; }
    handle_tool(c, mcp, "index_repository", args);
    free(args);
}

static void rest_status(cbm_http_conn_t *c, cbm_mcp_server_t *mcp, const char *name) {
    char *args = NULL;
    asprintf(&args, "{\"project\":\"%s\"}", name);
    handle_tool(c, mcp, "index_status", args);
    free(args);
}

static void rest_search(cbm_http_conn_t *c, const cbm_http_req_t *req,
                        cbm_mcp_server_t *mcp, const char *name) {
    char *q = query_get(req->query, "query");
    char *label = query_get(req->query, "label");
    char *name_pat = query_get(req->query, "name_pattern");
    char *limit = query_get(req->query, "limit");

    char *args = NULL;
    asprintf(&args,
             "{\"project\":\"%s\",\"query\":\"%s\",\"label\":\"%s\","
             "\"name_pattern\":\"%s\",\"limit\":%s}",
             name, q ? q : "", label ? label : "", name_pat ? name_pat : "",
             limit ? limit : "50");
    free(q); free(label); free(name_pat); free(limit);
    handle_tool(c, mcp, "search_graph", args);
    free(args);
}

static void rest_cypher(cbm_http_conn_t *c, const cbm_http_req_t *req,
                        cbm_mcp_server_t *mcp, const char *name) {
    /* Body: {"query": "..."} */
    char *query = NULL;
    if (req->body && req->body_len > 0) {
        const char *p = strstr(req->body, "\"query\"");
        if (p) {
            p = strchr(p, ':');
            if (p) {
                p = strchr(p, '"');
                if (p++) {
                    const char *e = strchr(p, '"');
                    if (e) {
                        query = malloc((size_t)(e - p) + 1);
                        if (query) {
                            memcpy(query, p, (size_t)(e - p));
                            query[e - p] = '\0';
                        }
                    }
                }
            }
        }
    }
    char *args = NULL;
    asprintf(&args, "{\"project\":\"%s\",\"query\":\"%s\"}", name, query ? query : "");
    free(query);
    handle_tool(c, mcp, "query_graph", args);
    free(args);
}

static void rest_trace(cbm_http_conn_t *c, const cbm_http_req_t *req,
                       cbm_mcp_server_t *mcp, const char *name) {
    char *fn = query_get(req->query, "function");
    char *dir = query_get(req->query, "direction");
    char *depth = query_get(req->query, "depth");
    char *args = NULL;
    asprintf(&args,
             "{\"project\":\"%s\",\"function_name\":\"%s\",\"direction\":\"%s\","
             "\"depth\":%s}",
             name, fn ? fn : "", dir ? dir : "callers", depth ? depth : "3");
    free(fn); free(dir); free(depth);
    handle_tool(c, mcp, "trace_path", args);
    free(args);
}

static void rest_code(cbm_http_conn_t *c, const cbm_http_req_t *req,
                      cbm_mcp_server_t *mcp, const char *name) {
    char *qn = query_get(req->query, "qn");
    char *args = NULL;
    asprintf(&args, "{\"project\":\"%s\",\"qualified_name\":\"%s\"}", name, qn ? qn : "");
    free(qn);
    handle_tool(c, mcp, "get_code_snippet", args);
    free(args);
}

static void rest_architecture(cbm_http_conn_t *c, const cbm_http_req_t *req,
                              cbm_mcp_server_t *mcp, const char *name) {
    char *aspects = query_get(req->query, "aspects");
    char *args = NULL;
    asprintf(&args, "{\"project\":\"%s\",\"aspects\":[\"%s\"]}", name,
             aspects ? aspects : "overview");
    free(aspects);
    handle_tool(c, mcp, "get_architecture", args);
    free(args);
}

static void rest_schema(cbm_http_conn_t *c, cbm_mcp_server_t *mcp, const char *name) {
    char *args = NULL;
    asprintf(&args, "{\"project\":\"%s\"}", name);
    handle_tool(c, mcp, "get_graph_schema", args);
    free(args);
}

static void rest_coverage(cbm_http_conn_t *c, const cbm_http_req_t *req,
                          cbm_mcp_server_t *mcp, const char *name) {
    char *paths = query_get(req->query, "paths");
    char *args = NULL;
    /* paths is comma-separated; build JSON array */
    if (paths) {
        /* crude: wrap each comma piece in quotes */
        char buf[2048] = "[";
        size_t o = 1;
        const char *p = paths;
        while (*p && o < sizeof(buf) - 4) {
            buf[o++] = '"';
            while (*p && *p != ',' && o < sizeof(buf) - 3) buf[o++] = *p++;
            buf[o++] = '"';
            if (*p == ',') { buf[o++] = ','; p++; }
        }
        buf[o++] = ']';
        buf[o] = '\0';
        asprintf(&args, "{\"project\":\"%s\",\"paths\":%s}", name, buf);
    } else {
        asprintf(&args, "{\"project\":\"%s\",\"scopes\":[\".\"]}", name);
    }
    free(paths);
    handle_tool(c, mcp, "check_index_coverage", args);
    free(args);
}

/* ── Dispatch ─────────────────────────────────────────────────────── */

bool cbm_rest_dispatch(cbm_http_conn_t *c, const cbm_http_req_t *req,
                       cbm_mcp_server_t *mcp) {
    const char *path = req->path;
    if (strncmp(path, "/api/v1/", 8) != 0) return false;

    bool is_get = strcmp(req->method, "GET") == 0;
    bool is_post = strcmp(req->method, "POST") == 0;
    bool is_delete = strcmp(req->method, "DELETE") == 0;

    /* GET /api/v1/projects */
    if (is_get && strcmp(path, "/api/v1/projects") == 0) {
        rest_list_projects(c, mcp);
        return true;
    }

    /* All other routes start with /api/v1/projects/{name}... */
    if (strncmp(path, "/api/v1/projects/", 17) != 0) {
        /* Future: /api/v1/chips/... */
        return false;
    }

    char name[256];
    if (!path_project_name(path, name, sizeof(name))) {
        reply_envelope(c, false, NULL, "BAD_REQUEST", "missing project name");
        return true;
    }

    const char *sub = path_after_project(path);
    const char *rest = sub ? sub : "";

    if (is_delete && rest[0] == '\0') {
        rest_delete_project(c, mcp, name);
        return true;
    }
    if (is_post && strcmp(rest, "/index") == 0) {
        rest_index(c, req, mcp, name);
        return true;
    }
    if (is_get && strcmp(rest, "/status") == 0) {
        rest_status(c, mcp, name);
        return true;
    }
    if (is_get && strcmp(rest, "/search") == 0) {
        rest_search(c, req, mcp, name);
        return true;
    }
    if (is_post && strcmp(rest, "/cypher") == 0) {
        rest_cypher(c, req, mcp, name);
        return true;
    }
    if (is_get && strcmp(rest, "/trace") == 0) {
        rest_trace(c, req, mcp, name);
        return true;
    }
    if (is_get && strcmp(rest, "/code") == 0) {
        rest_code(c, req, mcp, name);
        return true;
    }
    if (is_get && strcmp(rest, "/architecture") == 0) {
        rest_architecture(c, req, mcp, name);
        return true;
    }
    if (is_get && strcmp(rest, "/schema") == 0) {
        rest_schema(c, mcp, name);
        return true;
    }
    if (is_get && strcmp(rest, "/coverage") == 0) {
        rest_coverage(c, req, mcp, name);
        return true;
    }

    return false;
}
