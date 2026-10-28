package com.hisilicon.knowledge.platform.knowledge.query;

import com.hisilicon.knowledge.platform.commons.web.PageResult;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.BadcaseProblemType;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.BadcaseSource;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.BadcaseStatus;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.BadcaseTicketId;
import com.hisilicon.knowledge.platform.knowledge.domain.repository.BadcaseTicketRepository;
import com.hisilicon.knowledge.platform.knowledge.exception.BadcaseNotFoundException;
import com.hisilicon.knowledge.platform.knowledge.infrastructure.repository.BadcaseTicketPanacheRepository;
import com.hisilicon.knowledge.platform.knowledge.infrastructure.repository.BadcaseUserRepository;
import com.hisilicon.knowledge.platform.knowledge.infrastructure.repository.po.BadcaseTicketPO;
import com.hisilicon.knowledge.platform.knowledge.infrastructure.repository.po.BadcaseUserPO;
import com.hisilicon.knowledge.platform.knowledge.query.dto.AssigneeUserDTO;
import com.hisilicon.knowledge.platform.knowledge.query.dto.BadcaseDetailDTO;
import com.hisilicon.knowledge.platform.knowledge.query.dto.BadcaseDictDTO;
import com.hisilicon.knowledge.platform.knowledge.query.dto.BadcaseListItem;
import io.quarkus.hibernate.reactive.panache.common.WithSession;
import io.quarkus.panache.common.Page;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Badcase 查询服务（CQRS 查询侧）。
 */
@ApplicationScoped
public class BadcaseQueryService {

    private static final Logger logger = LoggerFactory.getLogger(BadcaseQueryService.class);

    private static final int DEFAULT_PAGE = 1;
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    private final BadcaseTicketPanacheRepository panacheRepo;
    private final BadcaseTicketRepository ticketRepository;
    private final BadcaseUserRepository userRepo;

    public BadcaseQueryService(BadcaseTicketPanacheRepository panacheRepo,
                               BadcaseTicketRepository ticketRepository,
                               BadcaseUserRepository userRepo) {
        this.panacheRepo = panacheRepo;
        this.ticketRepository = ticketRepository;
        this.userRepo = userRepo;
    }

    /**
     * 字典：来源列表 + 每个来源对应的问题类型集合 + 状态列表。
     */
    public BadcaseDictDTO dict() {
        List<BadcaseDictDTO.Option> sources = Arrays.stream(BadcaseSource.values())
                .map(s -> new BadcaseDictDTO.Option(s.name(), s.getLabel()))
                .toList();
        Map<String, List<BadcaseDictDTO.Option>> sourceTypes = new HashMap<>();
        for (BadcaseSource src : BadcaseSource.values()) {
            sourceTypes.put(src.name(), BadcaseProblemType.typesOf(src).stream()
                    .map(t -> new BadcaseDictDTO.Option(t.name(), t.getLabel()))
                    .toList());
        }
        List<BadcaseDictDTO.Option> statuses = Arrays.stream(BadcaseStatus.values())
                .map(s -> new BadcaseDictDTO.Option(s.name(), s.getLabel()))
                .toList();
        return new BadcaseDictDTO(sources, sourceTypes, statuses);
    }

    /**
     * 分页列表。
     */
    @WithSession
    public Uni<PageResult<BadcaseListItem>> list(String source, String problemType, String status,
                                                 String kbId, String keyword,
                                                 Integer page, Integer pageSize) {
        int pageNo = (page == null || page < 1) ? DEFAULT_PAGE : page;
        int size = (pageSize == null || pageSize < 1) ? DEFAULT_PAGE_SIZE : Math.min(pageSize, MAX_PAGE_SIZE);

        Map<String, Object> params = new HashMap<>();
        StringBuilder where = new StringBuilder("1 = 1");
        if (source != null && !source.isBlank()) {
            where.append(" and source = :source");
            params.put("source", source);
        }
        if (problemType != null && !problemType.isBlank()) {
            where.append(" and problemType = :problemType");
            params.put("problemType", problemType);
        }
        if (status != null && !status.isBlank()) {
            where.append(" and status = :status");
            params.put("status", status);
        }
        if (kbId != null && !kbId.isBlank()) {
            where.append(" and kbId = :kbId");
            params.put("kbId", kbId);
        }
        if (keyword != null && !keyword.isBlank()) {
            where.append(" and lower(description) like :keyword");
            params.put("keyword", "%" + keyword.toLowerCase(Locale.ROOT) + "%");
        }

        String orderBy = " order by submittedAt desc";
        logger.info("Badcase list query: where={}, page={}, size={}", where, pageNo, size);

        return panacheRepo.find(where.toString() + orderBy, params)
                .page(Page.of(pageNo - 1, size))
                .list()
                .flatMap(pos -> panacheRepo.count(where.toString(), params)
                        .map(total -> {
                            long totalPage = size == 0 ? 0 : (total + size - 1) / size;
                            List<BadcaseListItem> items = pos.stream()
                                    .map(this::toListItem)
                                    .toList();
                            return new PageResult<>(pageNo, size, totalPage, total, items);
                        }));
    }

    /**
     * 详情。
     */
    @WithSession
    public Uni<BadcaseDetailDTO> detail(String id) {
        return ticketRepository.findById(BadcaseTicketId.of(id))
                .map(opt -> opt.orElseThrow(() -> {
                    logger.warn("Badcase ticket not found: id={}", id);
                    return new BadcaseNotFoundException("Badcase 工单不存在");
                }))
                .map(this::toDetail);
    }

    /**
     * 搜索可指派的用户（按账号/姓名模糊，返回前 20 条，仅正常状态）。
     */
    @WithSession
    public Uni<List<AssigneeUserDTO>> searchAssignees(String keyword) {
        String kw = keyword == null ? "" : keyword.trim();
        String hql = "status = 1 and (lower(username) like :kw or lower(realName) like :kw) order by username";
        Map<String, Object> params = new HashMap<>();
        params.put("kw", "%" + kw.toLowerCase(Locale.ROOT) + "%");
        return userRepo.find(hql, params)
                .page(Page.of(0, 20))
                .list()
                .map(list -> list.stream()
                        .map(po -> new AssigneeUserDTO(po.username, po.realName))
                        .toList());
    }

    private BadcaseListItem toListItem(BadcaseTicketPO po) {
        return new BadcaseListItem(
                po.id, po.ticketNo,
                po.source, labelOfSource(po.source),
                po.problemType, labelOfProblemType(po.problemType),
                po.description,
                po.status, labelOfStatus(po.status),
                po.kbId, po.kbNameSnapshot,
                po.submittedByName, po.submittedAt,
                po.latestHandlingRemark, po.latestHandledByName, po.latestHandledAt
        );
    }

    private BadcaseDetailDTO toDetail(com.hisilicon.knowledge.platform.knowledge.domain.entity.BadcaseTicket t) {
        List<BadcaseDetailDTO.RelatedDoc> relatedDocs = null;
        if (t.getRelatedDocIds() != null && t.getRelatedDocNames() != null) {
            int n = Math.min(t.getRelatedDocIds().size(), t.getRelatedDocNames().size());
            relatedDocs = java.util.stream.IntStream.range(0, n)
                    .mapToObj(i -> new BadcaseDetailDTO.RelatedDoc(
                            t.getRelatedDocIds().get(i), t.getRelatedDocNames().get(i)))
                    .toList();
        }
        BadcaseDetailDTO.HandlingDTO handling = null;
        if (t.getLatestHandledAt() != null) {
            handling = new BadcaseDetailDTO.HandlingDTO(
                    t.getStatus().name(), t.getStatus().getLabel(),
                    t.getLatestHandlingRemark(), t.getLatestHandledByName(), t.getLatestHandledAt());
        }
        return new BadcaseDetailDTO(
                t.getId().value(), t.getTicketNo(),
                t.getSource().name(), t.getSource().getLabel(),
                t.getProblemType().name(), t.getProblemType().getLabel(),
                t.getDescription(),
                t.getStatus().name(), t.getStatus().getLabel(),
                t.getKbId(), t.getKbNameSnapshot(),
                relatedDocs,
                t.getSubmittedByName(), t.getSubmittedAt(),
                t.getSourcePayload(),
                handling
        );
    }

    private String labelOfSource(String code) {
        BadcaseSource s = BadcaseSource.fromCode(code);
        return s == null ? code : s.getLabel();
    }

    private String labelOfProblemType(String code) {
        BadcaseProblemType t = BadcaseProblemType.fromCode(code);
        return t == null ? code : t.getLabel();
    }

    private String labelOfStatus(String code) {
        BadcaseStatus s = BadcaseStatus.fromCode(code);
        return s == null ? code : s.getLabel();
    }
}
