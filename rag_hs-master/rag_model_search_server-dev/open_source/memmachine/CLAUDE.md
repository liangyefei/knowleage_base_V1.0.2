# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Repository Structure

This is a Python workspace (uv monorepo) with the following packages:

- `packages/server/` - Core server implementing episodic, semantic, and profile memory layers
- `packages/client/` - Python client SDK
- `packages/common/` - Shared REST API definitions
- `packages/meta/` - Meta-package that installs both server and client
- `packages/ts-client/` - TypeScript REST client
- `packages/skills/` - Skills for AI agents

Additional directories:
- `integrations/` - Framework integrations (LangChain, LangGraph, CrewAI, etc.)
- `examples/` - Example agents and demos
- `evaluation/` - Benchmarks and evaluation tools
- `tools/` - Utility scripts (migrations, chatgpt2memmachine, etc.)

## Environment Setup

```bash
uv sync                    # Install all dependencies
uv sync --all-extras       # With optional dev dependencies
```

Python version: 3.12+

## Build, Test, and Lint Commands

### Python (run from repo root)

```bash
# Linting and formatting
ruff check                 # Check for issues
ruff format                # Format code

# Type checking
ty check packages          # Type check all packages

# Testing
pytest                     # Run all tests (excludes integration by default)
pytest -k "test_name"      # Run tests matching keyword
pytest path/to/test.py     # Run specific test file
pytest path/to/test.py::test_function  # Run specific test
pytest -m integration      # Run integration tests only
pytest -m slow             # Run slow tests only
```

### TypeScript (run from `packages/ts-client/`)

```bash
npm install                # Install dependencies
npm run build              # Build the package
npm run lint               # Lint (ESLint)
npm run lint:fix           # Fix linting issues
npm run format             # Format (Prettier)
npm run test               # Run Jest tests
```

### Docker

```bash
./build-docker.sh          # Build container images (interactive)
```

## Architecture Overview

### Memory System

The server implements three memory types:

1. **Episodic Memory** (`episodic_memory/`) - Graph-based conversational context
   - Stored in Neo4j
   - Manages sessions, conversations, and episodes
   - Components: `episodic_memory.py`, `episodic_memory_manager.py`, `event_memory/`, `long_term_memory/`

2. **Semantic Memory** (`semantic_memory/`) - Vector-based retrieval
   - Uses vector databases (Qdrant, pgvector, usearch, sqlite-vec)
   - Manages embeddings and clusters for efficient retrieval
   - Components: `semantic_memory.py`, `semantic_ingestion.py`, `cluster_store/`, `storage/`

3. **Profile Memory** - SQL-based user facts and preferences
   - Stored in PostgreSQL or SQLite
   - Manages user profiles and declarative facts

### Key Components

- `server/` - FastAPI REST server and MCP server implementation
- `retrieval_agent/` - Agent system for intelligent memory retrieval
- `common/` - Shared utilities, configuration, and helpers
- `installation/` - Database migration and setup scripts

### Service Layer

The `service_locator.py` files provide dependency injection for:
- Memory managers (episodic, semantic)
- Database connections (Neo4j, PostgreSQL)
- Embedding providers (OpenAI, Bedrock, etc.)
- LLM providers (for retrieval agent)

## Code Style

- **Python**: Ruff for formatting and linting, `ty` for type checking
- **TypeScript**: ESLint + Prettier
- Use type hints everywhere in Python
- Follow existing patterns when editing code
- Prefer small, focused functions with clear responsibilities

## Testing Notes

- Tests use pytest with async support
- Integration tests require running databases (Neo4j, PostgreSQL)
- Use `@pytest.mark.integration` and `@pytest.mark.slow` for special tests
- Test files are in `*_tests/` directories within each package

## Examples and Demos

The `examples/` directory contains working examples and verification scripts:

### Wuxia Knowledge Graph (武侠小说知识图谱)

Demonstrates domain-specific knowledge graph construction on top of MemMachine:

- `wuxia_minimal_verify.py` - Minimal verification script for ontology-based entity/relation extraction
- `wuxia_graph_api.py` - FastAPI web service for graph visualization
- `wuxia_graph_viewer.html` - Web interface with interactive graph visualization
- `wuxia_README.md` - Usage documentation
- `sample_wuxia_text.txt` - Sample novel text for testing

Run with:
```bash
cd examples
./run_wuxia_verify.sh    # Run extraction verification
./run_graph_web.sh       # Start web visualization server
```

### Other Examples

- `memmachine_client_demo.py` - Python client SDK demonstration
- `MemMachine_V2_Complete_Guide.ipynb` - Interactive Jupyter notebook guide
- `simple_chatbot/`, `qwen_agent/`, `openai_agent/` - Agent framework examples

## MCP Server

The server includes MCP (Model Context Protocol) support:
- `memmachine-mcp-stdio` - For Claude Desktop (stdio mode)
- `memmachine-mcp-http` - For web clients (HTTP mode)

## Important: Docker Deployment

Never automatically stop or remove running Docker containers. If conflicts occur, inform the user to resolve manually.
