import { ref, computed, watch } from 'vue'
import {
  getNavTree,
  getMetadataDictOptions,
  searchDocuments,
  SEARCH_DOC_TYPES,
  type MetadataDictOption,
} from '@/services/knowledge.service'
import {
  findFolderById,
  buildFolderPath,
  navTreeToFolders,
  type IKbFolder,
} from '@/data/knowledge-bases'

export const KB_DOC_PAGE_SIZE = 100

/** 切片查看：仅已向量化文档 */
export const CHUNK_VIEW_PROCESS_STATUSES = ['COMPLETED'] as const

export interface KbDocOption {
  /** 组合唯一标识：docId||version */
  id: string
  /** 原始文档 ID */
  docId: string
  title: string
  version: string
  fileType: string
}

/** 从组合 id 中提取原始 docId */
export function extractDocId(compositeId: string): string {
  const idx = compositeId.lastIndexOf('||')
  return idx > 0 ? compositeId.slice(0, idx) : compositeId
}

export interface UseKbDocumentOptionsConfig {
  /** 文档向量化状态过滤，默认已向量化 COMPLETED */
  processStatuses?: string[]
  /** 文档生命周期状态过滤，默认已发布 PUBLISHED */
  statuses?: string[]
  /** 是否包含子孙分类下的文档 */
  includeDescendants?: boolean
}

export function useKbDocumentOptions(config?: UseKbDocumentOptionsConfig) {
  const processStatuses = config?.processStatuses ?? [...CHUNK_VIEW_PROCESS_STATUSES]
  const docStatuses = config?.statuses ?? ['PUBLISHED']
  const includeDescendants = config?.includeDescendants ?? false

  const folders = ref<IKbFolder[]>([])
  const selectedFolderId = ref<string | null>(null)
  /** 选中节点快照（搜索结果里选中后清空搜索仍能查文档） */
  const selectedFolderSnapshot = ref<IKbFolder | null>(null)
  const expandedFolderIds = ref<Set<string>>(new Set())
  const loadingChildIds = ref<Set<string>>(new Set())
  const folderPickerOpen = ref(false)
  const folderSearch = ref('')
  const folderSearchResults = ref<IKbFolder[]>([])
  const folderSearchLoading = ref(false)
  const folderTreeLoading = ref(false)
  let folderSearchTimer: ReturnType<typeof setTimeout> | null = null

  const selectedVersion = ref('all')
  const selectedDocId = ref('')
  const metadataDictOptions = ref<MetadataDictOption[]>([])
  const kbDocs = ref<KbDocOption[]>([])
  const docLoading = ref(false)
  const docPage = ref(1)
  const docTotalPage = ref(1)
  const docKeyword = ref('')
  const selectedDocTitleCache = ref('')
  let docSeq = 0
  let pauseWatch = false
  let keywordTimer: ReturnType<typeof setTimeout> | null = null

  const selectedFolder = computed(() => {
    if (!selectedFolderId.value) return null
    return findFolderById(folders.value, selectedFolderId.value)
      || (selectedFolderSnapshot.value?.id === selectedFolderId.value ? selectedFolderSnapshot.value : null)
      || findFolderById(folderSearchResults.value, selectedFolderId.value)
  })

  const selectedKbId = computed(() => selectedFolder.value?.kbId ?? '')

  const selectedFolderLabel = computed(() => {
    if (!selectedFolderId.value) return ''
    const path = buildFolderPath(folders.value, selectedFolderId.value)
    if (path.length) return path.map((f) => f.name).join(' / ')
    return selectedFolder.value?.name || ''
  })

  const versionOptions = computed(() =>
    metadataDictOptions.value.find((o) => o.metaKey === 'version')?.options || [],
  )

  const selectedDocTitle = computed(() =>
    kbDocs.value.find((d) => d.id === selectedDocId.value)?.title || selectedDocTitleCache.value,
  )

  const docHasMore = computed(() => docPage.value < docTotalPage.value)

  const displayFolders = computed(() =>
    folderSearch.value.trim() ? folderSearchResults.value : folders.value,
  )

  function mapRecords(records: unknown[]): KbDocOption[] {
    return records
      .map((r) => {
        const row = (r ?? {}) as Record<string, unknown>
        const docId = String(row.id ?? row.documentId ?? '')
        const version = String(row.version ?? '')
        return {
          id: `${docId}||${version}`,
          docId,
          title: String(row.title ?? row.name ?? '').trim(),
          version,
          fileType: String(row.fileType ?? row.type ?? ''),
        }
      })
      .filter((d) => d.docId)
  }

  function ensureDocInList(id: string, title: string, version = '') {
    if (!id) return
    const compositeId = version ? `${id}||${version}` : id
    if (kbDocs.value.some((d) => d.id === compositeId)) return
    kbDocs.value = [{ id: compositeId, docId: id, title: title || id, version, fileType: '' }, ...kbDocs.value]
  }

  function restoreSelectedDoc(id: string, title: string, version = '') {
    if (!id) return
    selectedDocTitleCache.value = title || id
    ensureDocInList(id, title, version)
    selectedDocId.value = version ? `${id}||${version}` : id
  }

  function mergeFolderChildren(list: IKbFolder[], id: string, children: IKbFolder[]): IKbFolder[] {
    return list.map((f) =>
      f.id === id
        ? { ...f, children, childrenLoaded: true }
        : { ...f, children: f.children?.length ? mergeFolderChildren(f.children, id, children) : f.children },
    )
  }

  async function loadFolderChildren(id: string): Promise<boolean> {
    const inSearch = !!folderSearch.value.trim()
    const tree = inSearch ? folderSearchResults.value : folders.value
    const node = findFolderById(tree, id)
    if (!node || !node.hasChildren || node.childrenLoaded) return true
    loadingChildIds.value = new Set([...loadingChildIds.value, id])
    try {
      const childNodes = await getNavTree({ parentId: node.nodeId })
      const merged = mergeFolderChildren(tree, id, navTreeToFolders(childNodes, id))
      if (inSearch) folderSearchResults.value = merged
      else folders.value = merged
      return true
    } catch {
      return false
    } finally {
      const s = new Set(loadingChildIds.value)
      s.delete(id)
      loadingChildIds.value = s
    }
  }

  async function toggleFolderExpand(id: string) {
    const next = new Set(expandedFolderIds.value)
    if (next.has(id)) {
      next.delete(id)
      expandedFolderIds.value = next
      return
    }
    next.add(id)
    expandedFolderIds.value = next
    const ok = await loadFolderChildren(id)
    if (!ok) {
      next.delete(id)
      expandedFolderIds.value = next
    }
  }

  function selectFolder(id: string) {
    const node = findFolderById(folders.value, id)
      || findFolderById(folderSearchResults.value, id)
    selectedFolderSnapshot.value = node
    selectedFolderId.value = id
    folderPickerOpen.value = false
    folderSearch.value = ''
    folderSearchResults.value = []
  }

  function onFolderSearchInput(value: string) {
    folderSearch.value = value
    if (folderSearchTimer) clearTimeout(folderSearchTimer)
    const keyword = value.trim()
    if (!keyword) {
      folderSearchResults.value = []
      folderSearchLoading.value = false
      return
    }
    folderSearchLoading.value = true
    folderSearchTimer = setTimeout(async () => {
      try {
        const nodes = await getNavTree({ keyword })
        folderSearchResults.value = navTreeToFolders(nodes || [])
      } catch {
        folderSearchResults.value = []
      } finally {
        folderSearchLoading.value = false
      }
    }, 300)
  }

  async function loadKbDocuments(append = false) {
    const folder = selectedFolder.value
    const kbId = folder?.kbId
    if (!kbId) {
      kbDocs.value = []
      docPage.value = 1
      docTotalPage.value = 1
      return
    }
    if (append && (docLoading.value || docPage.value >= docTotalPage.value)) return
    const nextPage = append ? docPage.value + 1 : 1
    const reqId = ++docSeq
    docLoading.value = true
    try {
      const payload: Record<string, unknown> = {
        kbId,
        categoryId: folder.nodeType === 'CATEGORY' ? folder.nodeId : undefined,
        includeDescendants,
        page: nextPage,
        pageSize: KB_DOC_PAGE_SIZE,
        docName: docKeyword.value.trim() || undefined,
        docTypes: [...SEARCH_DOC_TYPES],
        statuses: [...docStatuses],
        metadata: selectedVersion.value !== 'all' ? { version: selectedVersion.value } : undefined,
      }
      if (processStatuses.length > 0) {
        payload.processStatuses = [...processStatuses]
      }
      const res = await searchDocuments(payload as any)
      if (reqId !== docSeq) return
      const mapped = mapRecords((res.records || []) as unknown[])
      if (append) {
        const existing = new Set(kbDocs.value.map((d) => d.id))
        kbDocs.value = [...kbDocs.value, ...mapped.filter((d) => !existing.has(d.id))]
      } else {
        kbDocs.value = mapped
      }
      docPage.value = nextPage
      const byTotalRow = res.totalRow ? Math.max(1, Math.ceil(res.totalRow / KB_DOC_PAGE_SIZE)) : 0
      docTotalPage.value = res.totalPage
        || byTotalRow
        || (mapped.length >= KB_DOC_PAGE_SIZE ? nextPage + 1 : nextPage)
    } catch {
      if (reqId !== docSeq) return
      if (!append) kbDocs.value = []
    } finally {
      if (reqId === docSeq) docLoading.value = false
    }
  }

  function loadMoreDocs() {
    void loadKbDocuments(true)
  }

  function onDocListScroll(e: Event) {
    const el = e.target as HTMLElement
    if (!el || docLoading.value || !docHasMore.value) return
    if (el.scrollTop + el.clientHeight >= el.scrollHeight - 32) loadMoreDocs()
  }

  function onDocKeywordChange(value: string) {
    docKeyword.value = value
    if (keywordTimer) clearTimeout(keywordTimer)
    keywordTimer = setTimeout(() => {
      void loadKbDocuments(false)
    }, value.trim() ? 300 : 0)
  }

  watch(selectedDocId, (id) => {
    if (!id) {
      selectedDocTitleCache.value = ''
      return
    }
    const found = kbDocs.value.find((d) => d.id === id)
    if (found?.title) selectedDocTitleCache.value = found.title
  })

  async function initKbDocFilters() {
    pauseWatch = true
    try {
      folderTreeLoading.value = true
      try {
        const nodes = await getNavTree()
        folders.value = navTreeToFolders(nodes || [])
        if (!selectedFolderId.value && folders.value.length) {
          selectedFolderId.value = folders.value[0].id
          selectedFolderSnapshot.value = folders.value[0]
        }
      } catch {
        folders.value = []
        selectedFolderId.value = null
      } finally {
        folderTreeLoading.value = false
      }
      try {
        metadataDictOptions.value = await getMetadataDictOptions()
      } catch {
        metadataDictOptions.value = []
      }
      await loadKbDocuments(false)
    } finally {
      pauseWatch = false
    }
  }

  watch([selectedFolderId, selectedVersion], () => {
    if (pauseWatch) return
    if (keywordTimer) clearTimeout(keywordTimer)
    selectedDocId.value = ''
    docKeyword.value = ''
    void loadKbDocuments(false)
  }, { flush: 'sync' })

  return {
    folders,
    displayFolders,
    selectedFolderId,
    selectedFolder,
    selectedFolderLabel,
    selectedKbId,
    expandedFolderIds,
    loadingChildIds,
    folderPickerOpen,
    folderSearch,
    folderSearchLoading,
    folderTreeLoading,
    toggleFolderExpand,
    selectFolder,
    onFolderSearchInput,
    selectedVersion,
    selectedDocId,
    selectedDocTitle,
    versionOptions,
    kbDocs,
    docLoading,
    docHasMore,
    docKeyword,
    initKbDocFilters,
    loadMoreDocs,
    onDocListScroll,
    onDocKeywordChange,
    restoreSelectedDoc,
  }
}
