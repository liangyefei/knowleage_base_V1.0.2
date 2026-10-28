/*
 * pass_dynamic_dispatch.c — Synthesized call edges for dynamic dispatch patterns.
 *
 * Static tree-sitter extraction misses computed/indirect calls: callback
 * registrations, EventEmitter patterns, React setState→render, and framework
 * middleware chains. This pass bridges those gaps so end-to-end call paths
 * connect without breaks.
 *
 * Runs as a pre-dump pass over the fully-populated graph buffer. Every
 * synthesized edge is tagged:
 *   provenance: "heuristic"
 *   synthesizedBy: "<channel>"
 *
 * Implemented patterns (name-based + edge structure):
 *   P1 — EventEmitter:  registrar (on/addListener) ↔ dispatcher (emit/trigger)
 *   P2 — React setState→render
 *   P3 — Observer callback: addEventListener/subscribe → handler
 *   P4 — Express-style middleware routes: app.use/get/post → handler
 */

#include "foundation/constants.h"

#include "pipeline/pipeline.h"
#include "pipeline/pipeline_internal.h"
#include "graph_buffer/graph_buffer.h"
#include "foundation/log.h"

#include <ctype.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

enum { DD_MAX_FANOUT = 20, DD_PROPS_CAP = 256, DD_BUF_SZ = 512 };

/* ── itoa_buf helper ──────────────────────────────────────────────── */

static const char *itoa_buf(int v) {
    static _Thread_local char buf[4][16];
    static _Thread_local int idx = 0;
    idx = (idx + 1) & 3;
    snprintf(buf[idx], sizeof(buf[idx]), "%d", v);
    return buf[idx];
}

/* ── String helpers ───────────────────────────────────────────────── */

static bool str_starts_with(const char *s, const char *prefix) {
    return s && prefix && strncmp(s, prefix, strlen(prefix)) == 0;
}

static bool str_contains(const char *haystack, const char *needle) {
    return haystack && needle && strstr(haystack, needle) != NULL;
}

/* ── Edge emission ─────────────────────────────────────────────────── */

static int dd_emit_edge(cbm_gbuf_t *gbuf, int64_t src_id, int64_t tgt_id,
                         const char *channel) {
    if (!gbuf || src_id <= 0 || tgt_id <= 0 || src_id == tgt_id) return 0;

    char props[DD_PROPS_CAP];
    int n = snprintf(props, sizeof(props),
                     "{\"provenance\":\"heuristic\",\"synthesizedBy\":\"%s\"}", channel);
    if (n < 0 || n >= (int)sizeof(props)) return 0;

    int64_t edge_id =
        cbm_gbuf_insert_edge(gbuf, src_id, tgt_id, "SYNTHESIZED_CALL", props);
    return edge_id > 0 ? 1 : 0;
}

/* ── Naming heuristics ────────────────────────────────────────────── */

static bool dd_is_registrar_name(const char *name) {
    if (!name) return false;
    return str_starts_with(name, "on") || str_starts_with(name, "add") ||
           str_contains(name, "subscribe") || str_contains(name, "listen") ||
           str_contains(name, "register") || str_contains(name, "watch");
}

static bool dd_is_dispatcher_name(const char *name) {
    if (!name) return false;
    return str_contains(name, "emit") || str_contains(name, "trigger") ||
           str_contains(name, "dispatch") || str_contains(name, "notify") ||
           str_contains(name, "fire") || str_contains(name, "publish") ||
           str_contains(name, "flush");
}

static bool dd_is_handler_name(const char *name) {
    if (!name) return false;
    if (str_starts_with(name, "handle") || str_starts_with(name, "on")) return true;
    if (strstr(name, "Handler") || strstr(name, "Callback") ||
        strstr(name, "Listener"))
        return true;
    return false;
}

static bool dd_is_route_definer(const char *name) {
    if (!name) return false;
    static const char *methods[] = {
        "use",    "get",    "post",    "put",     "delete",
        "patch",  "head",   "options", "all",     "route",
        "Router", "Group",  "Handle",  "HandleFunc",
        "GET",    "POST",   "PUT",     "DELETE",  "PATCH",
        NULL};
    for (int i = 0; methods[i]; i++) {
        if (strcmp(name, methods[i]) == 0) return true;
    }
    return false;
}

/* ── P1+P3: EventEmitter + Observer synthesis ────────────────────── */
/*
 * Scans CALLS edges for:
 *   handler → registrar (on/addEventListener/subscribe)   [registration]
 *   dispatcher (emit/trigger/dispatch) → emit method      [dispatch]
 *
 * When a registrar and dispatcher share the same file (scope), pairs the
 * dispatcher with every handler that registered through that registrar.
 */
static int dd_synthesize_event_emitter(cbm_pipeline_ctx_t *ctx) {
    if (!ctx || !ctx->gbuf) return 0;

    /* Collect all CALLS edges in one pass */
    const cbm_gbuf_edge_t **edges = NULL;
    int edge_count = 0;
    if (cbm_gbuf_find_edges_by_type(ctx->gbuf, "CALLS", &edges, &edge_count) != 0)
        return 0;
    if (!edges || edge_count <= 0) return 0;

    int added = 0;

    for (int i = 0; i < edge_count && added < DD_MAX_FANOUT * 4; i++) {
        const cbm_gbuf_edge_t *e = edges[i];
        if (!e || !e->type) continue;

        const cbm_gbuf_node_t *src = cbm_gbuf_find_by_id(ctx->gbuf, e->source_id);
        const cbm_gbuf_node_t *tgt = cbm_gbuf_find_by_id(ctx->gbuf, e->target_id);
        if (!src || !tgt || !src->name || !tgt->name) continue;

        /* Case A: handler calls registrar → find dispatcher in same file */
        if (dd_is_handler_name(src->name) && dd_is_registrar_name(tgt->name)) {
            for (int j = 0; j < edge_count && added < DD_MAX_FANOUT * 4; j++) {
                const cbm_gbuf_edge_t *e2 = edges[j];
                if (e2 == e) continue;
                const cbm_gbuf_node_t *dispatcher =
                    cbm_gbuf_find_by_id(ctx->gbuf, e2->source_id);
                if (!dispatcher || !dispatcher->name || !dd_is_dispatcher_name(dispatcher->name))
                    continue;
                if (src->file_path && dispatcher->file_path &&
                    strcmp(src->file_path, dispatcher->file_path) == 0) {
                    added += dd_emit_edge(ctx->gbuf, dispatcher->id, src->id, "observer-callback");
                }
            }
            continue;
        }

        /* Case B: dispatcher calls emit → pair with handlers in same file */
        if (dd_is_dispatcher_name(src->name)) {
            for (int j = 0; j < edge_count && added < DD_MAX_FANOUT * 4; j++) {
                const cbm_gbuf_edge_t *e2 = edges[j];
                if (e2 == e) continue;
                const cbm_gbuf_node_t *reg = cbm_gbuf_find_by_id(ctx->gbuf, e2->target_id);
                if (!reg || !reg->name || !dd_is_registrar_name(reg->name)) continue;
                const cbm_gbuf_node_t *handler = cbm_gbuf_find_by_id(ctx->gbuf, e2->source_id);
                if (!handler || !handler->name || !dd_is_handler_name(handler->name)) continue;
                if (src->file_path && handler->file_path &&
                    strcmp(src->file_path, handler->file_path) == 0) {
                    added += dd_emit_edge(ctx->gbuf, src->id, handler->id, "event-emitter");
                }
            }
        }
    }

    return added;
}

/* ── P2: React setState → render ──────────────────────────────────── */
/*
 * For class components: a method calling this.setState() triggers render().
 * Detection: CALLS edge from a Method node whose QN prefix matches a Class,
 * and that class has a render() method.
 */
static int dd_synthesize_react_setstate(cbm_pipeline_ctx_t *ctx) {
    if (!ctx || !ctx->gbuf) return 0;

    const cbm_gbuf_edge_t **edges = NULL;
    int edge_count = 0;
    if (cbm_gbuf_find_edges_by_type(ctx->gbuf, "CALLS", &edges, &edge_count) != 0)
        return 0;
    if (!edges || edge_count <= 0) return 0;

    int added = 0;

    for (int i = 0; i < edge_count && added < DD_MAX_FANOUT; i++) {
        const cbm_gbuf_edge_t *e = edges[i];
        if (!e) continue;

        const cbm_gbuf_node_t *src = cbm_gbuf_find_by_id(ctx->gbuf, e->source_id);
        const cbm_gbuf_node_t *tgt = cbm_gbuf_find_by_id(ctx->gbuf, e->target_id);
        if (!src || !tgt || !src->qualified_name || !tgt->name) continue;

        /* Only interested in Method nodes calling setState */
        if (!src->label || strcmp(src->label, "Method") != 0) continue;
        if (!str_contains(tgt->name, "setState")) continue;

        /* Extract class QN: ClassName.methodName → ClassName */
        const char *last_dot = strrchr(src->qualified_name, '.');
        if (!last_dot || last_dot == src->qualified_name) continue;

        size_t class_len = (size_t)(last_dot - src->qualified_name);
        char render_qn[DD_BUF_SZ];
        int n = snprintf(render_qn, sizeof(render_qn), "%.*s.render", (int)class_len,
                         src->qualified_name);
        if (n <= 0 || n >= (int)sizeof(render_qn)) continue;

        const cbm_gbuf_node_t *render = cbm_gbuf_find_by_qn(ctx->gbuf, render_qn);
        if (render && render->id != src->id) {
            added += dd_emit_edge(ctx->gbuf, src->id, render->id, "react-setstate-render");
        }
    }

    return added;
}

/* ── P4: Express-style middleware / route chains ──────────────────── */
/*
 * When a function makes CALLS edges to a route-defining method (use/get/post)
 * AND to a handler function, synthesize a route→handler edge so the agent
 * can trace from route definition to handler body in one explore call.
 */
static int dd_synthesize_routes(cbm_pipeline_ctx_t *ctx) {
    if (!ctx || !ctx->gbuf) return 0;

    const cbm_gbuf_edge_t **edges = NULL;
    int edge_count = 0;
    if (cbm_gbuf_find_edges_by_type(ctx->gbuf, "CALLS", &edges, &edge_count) != 0)
        return 0;
    if (!edges || edge_count <= 0) return 0;

    int added = 0;

    for (int i = 0; i < edge_count && added < DD_MAX_FANOUT * 4; i++) {
        const cbm_gbuf_edge_t *e = edges[i];
        if (!e) continue;

        const cbm_gbuf_node_t *src = cbm_gbuf_find_by_id(ctx->gbuf, e->source_id);
        const cbm_gbuf_node_t *tgt = cbm_gbuf_find_by_id(ctx->gbuf, e->target_id);
        if (!src || !tgt || !tgt->name) continue;

        if (!dd_is_route_definer(tgt->name)) continue;

        /* Find other calls from the same source that go to Function handlers */
        for (int j = 0; j < edge_count && added < DD_MAX_FANOUT * 4; j++) {
            const cbm_gbuf_edge_t *e2 = edges[j];
            if (!e2 || e2 == e) continue;
            if (e2->source_id != e->source_id) continue;
            if (e2->target_id == e->target_id) continue;

            const cbm_gbuf_node_t *handler = cbm_gbuf_find_by_id(ctx->gbuf, e2->target_id);
            if (!handler || !handler->label) continue;
            if (strcmp(handler->label, "Function") != 0 &&
                strcmp(handler->label, "Method") != 0)
                continue;

            added += dd_emit_edge(ctx->gbuf, tgt->id, handler->id, "framework-route");
        }
    }

    return added;
}

/* ── Entry point ──────────────────────────────────────────────────── */

int cbm_pipeline_pass_dynamic_dispatch(cbm_pipeline_ctx_t *ctx) {
    if (!ctx || !ctx->gbuf || !ctx->project_name) return 0;

    int n1 = dd_synthesize_event_emitter(ctx);
    int n2 = dd_synthesize_react_setstate(ctx);
    int n3 = dd_synthesize_routes(ctx);
    int total = n1 + n2 + n3;

    if (total > 0) {
        cbm_log_info("dd.synthesized", "edges", itoa_buf(total), "emitter", itoa_buf(n1),
                     "react", itoa_buf(n2), "routes", itoa_buf(n3));
    }
    return total;
}
