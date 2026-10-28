/*
 * rest_api.h — REST API layer (translates REST → MCP tool calls).
 *
 * Exposes the same functionality as the MCP tools through RESTful
 * HTTP endpoints under /api/v1/. Each endpoint parses path/query/body into
 * MCP tool arguments, calls cbm_mcp_handle_tool, and wraps the result in a
 * REST envelope {ok, data, error}.
 */
#ifndef CBM_REST_API_H
#define CBM_REST_API_H

#include "ui/httpd.h"
#include "mcp/mcp.h"

/* Dispatch a /api/v1/ REST request. Returns true if the path matched a
 * REST route (response sent), false otherwise (caller should 404). */
bool cbm_rest_dispatch(cbm_http_conn_t *c, const cbm_http_req_t *req,
                       cbm_mcp_server_t *mcp);

#endif /* CBM_REST_API_H */
