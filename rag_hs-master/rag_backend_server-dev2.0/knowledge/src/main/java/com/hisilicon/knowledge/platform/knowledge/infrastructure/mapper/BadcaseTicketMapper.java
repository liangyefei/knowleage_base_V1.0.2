package com.hisilicon.knowledge.platform.knowledge.infrastructure.mapper;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.BadcaseTicket;
import com.hisilicon.knowledge.platform.knowledge.infrastructure.repository.po.BadcaseTicketPO;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * Badcase 工单转换器：PO ↔ Domain Entity。
 * <p>jsonb 列在 PO 层是 String，领域层是 List/Map；本转换器用 ObjectMapper 做序列化/反序列化。</p>
 */
@ApplicationScoped
public class BadcaseTicketMapper {

    private static final Logger logger = LoggerFactory.getLogger(BadcaseTicketMapper.class);

    private final ObjectMapper objectMapper;

    @Inject
    public BadcaseTicketMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public BadcaseTicket toDomain(BadcaseTicketPO po) {
        if (po == null) return null;
        return BadcaseTicket.builder()
                .id(com.hisilicon.knowledge.platform.knowledge.domain.entity.BadcaseTicketId.of(po.id))
                .ticketNo(po.ticketNo)
                .source(com.hisilicon.knowledge.platform.knowledge.domain.entity.BadcaseSource.valueOf(po.source))
                .problemType(com.hisilicon.knowledge.platform.knowledge.domain.entity.BadcaseProblemType.valueOf(po.problemType))
                .description(po.description)
                .status(po.status == null
                        ? com.hisilicon.knowledge.platform.knowledge.domain.entity.BadcaseStatus.PENDING
                        : com.hisilicon.knowledge.platform.knowledge.domain.entity.BadcaseStatus.valueOf(po.status))
                .kbId(po.kbId)
                .kbNameSnapshot(po.kbNameSnapshot)
                .relatedDocIds(readStringList(po.relatedDocIds))
                .relatedDocNames(readStringList(po.relatedDocNames))
                .sourcePayload(readMap(po.sourcePayload))
                .latestHandlingRemark(po.latestHandlingRemark)
                .latestHandledBy(po.latestHandledBy)
                .latestHandledByName(po.latestHandledByName)
                .latestHandledAt(po.latestHandledAt)
                .submittedBy(po.submittedBy)
                .submittedByName(po.submittedByName)
                .submittedAt(po.submittedAt)
                .createdAt(po.createdAt)
                .updatedAt(po.updatedAt)
                .build();
    }

    public BadcaseTicketPO toPO(BadcaseTicket entity) {
        if (entity == null) return null;
        BadcaseTicketPO po = new BadcaseTicketPO();
        po.id = entity.getId().value();
        po.ticketNo = entity.getTicketNo();
        po.source = entity.getSource().name();
        po.problemType = entity.getProblemType().name();
        po.description = entity.getDescription();
        po.status = entity.getStatus().name();
        po.kbId = entity.getKbId();
        po.kbNameSnapshot = entity.getKbNameSnapshot();
        po.relatedDocIds = writeStringList(entity.getRelatedDocIds());
        po.relatedDocNames = writeStringList(entity.getRelatedDocNames());
        po.sourcePayload = writeMap(entity.getSourcePayload());
        po.latestHandlingRemark = entity.getLatestHandlingRemark();
        po.latestHandledBy = entity.getLatestHandledBy();
        po.latestHandledByName = entity.getLatestHandledByName();
        po.latestHandledAt = entity.getLatestHandledAt();
        po.submittedBy = entity.getSubmittedBy();
        po.submittedByName = entity.getSubmittedByName();
        po.submittedAt = entity.getSubmittedAt();
        po.createdAt = entity.getCreatedAt();
        po.updatedAt = entity.getUpdatedAt();
        return po;
    }

    private List<String> readStringList(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (JsonProcessingException e) {
            logger.warn("Failed to parse jsonb as List<String>: {}", json, e);
            return null;
        }
    }

    private Map<String, Object> readMap(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (JsonProcessingException e) {
            logger.warn("Failed to parse jsonb as Map: {}", json, e);
            return null;
        }
    }

    private String writeStringList(List<String> list) {
        if (list == null) return null;
        try {
            return objectMapper.writeValueAsString(list);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize List<String>", e);
        }
    }

    private String writeMap(Map<String, Object> map) {
        if (map == null) return null;
        try {
            return objectMapper.writeValueAsString(map);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize Map", e);
        }
    }
}
