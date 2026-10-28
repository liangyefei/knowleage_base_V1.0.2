/*
 * yaml.h — Minimal YAML parser for config files.
 *
 * Handles the subset needed by .cgrconfig:
 *   - key: value pairs (string, float, bool)
 *   - Nested maps (indentation-based)
 *   - String lists (- item)
 *   - Comment lines (#)
 *
 * NOT a general YAML parser — no multiline strings, anchors, flow style, etc.
 */
#ifndef CBM_YAML_H
#define CBM_YAML_H

#include <stdbool.h>

typedef struct cbm_yaml_node cbm_yaml_node_t;

/* Parse a YAML string into a tree. Returns NULL on error.
 * Caller must free with cbm_yaml_free(). */
cbm_yaml_node_t *cbm_yaml_parse(const char *text, int len);

/* Free a parsed YAML tree. */
void cbm_yaml_free(cbm_yaml_node_t *root);

/* Get a scalar value by dot-separated path (e.g. "http_linker.min_confidence").
 * Returns NULL if not found or not a scalar. */
const char *cbm_yaml_get_str(const cbm_yaml_node_t *root, const char *path);

/* Get a float value by path, returning default_val if not found. */
double cbm_yaml_get_float(const cbm_yaml_node_t *root, const char *path, double default_val);

/* Get a bool value by path, returning default_val if not found.
 * Recognizes: true/false, yes/no, on/off (case-insensitive). */
bool cbm_yaml_get_bool(const cbm_yaml_node_t *root, const char *path, bool default_val);

/* Get a list of string values at a path (e.g. "http_linker.exclude_paths").
 * Writes up to max_out pointers into out[]. Pointers are owned by the YAML tree.
 * Returns count of items written. */
int cbm_yaml_get_str_list(const cbm_yaml_node_t *root, const char *path, const char **out,
                          int max_out);

/* Check if a node at the given path exists. */
bool cbm_yaml_has(const cbm_yaml_node_t *root, const char *path);

/* ── Structured access (for complex configs, chip specs, etc.) ──── */

/* Return the child node at path (NULL if not found or not a map/list). */
const cbm_yaml_node_t *cbm_yaml_get_node(const cbm_yaml_node_t *root, const char *path);

/* Return the number of children of a map or list node. Returns 0 for scalars. */
int cbm_yaml_child_count(const cbm_yaml_node_t *node);

/* Return the i-th child (NULL if out of bounds). List items have key=NULL. */
const cbm_yaml_node_t *cbm_yaml_child_at(const cbm_yaml_node_t *node, int i);

/* Return a child's key string, or NULL for list items and root. */
const char *cbm_yaml_child_key(const cbm_yaml_node_t *node, int i);

/* Return a child's scalar value, or NULL for map/list children. */
const char *cbm_yaml_child_value(const cbm_yaml_node_t *node, int i);

/* Find a child of a MAP node by key (NULL if not found). */
const cbm_yaml_node_t *cbm_yaml_map_get(const cbm_yaml_node_t *map, const char *key);

/* Number of list items under path (0 if not a list or doesn't exist). */
int cbm_yaml_list_len(const cbm_yaml_node_t *root, const char *path);

/* Get the i-th item from a list at path (NULL if out of bounds). */
const cbm_yaml_node_t *cbm_yaml_list_item(const cbm_yaml_node_t *root, const char *path, int i);

/* Get the scalar value of a node (NULL if not a scalar). */
const char *cbm_yaml_node_value(const cbm_yaml_node_t *node);

/* Get the type of a node: "map", "list", or "scalar". */
const char *cbm_yaml_node_type_str(const cbm_yaml_node_t *node);

/* True if node is a list type. */
bool cbm_yaml_node_is_list(const cbm_yaml_node_t *node);

/* True if node is a map type. */
bool cbm_yaml_node_is_map(const cbm_yaml_node_t *node);

#endif /* CBM_YAML_H */
