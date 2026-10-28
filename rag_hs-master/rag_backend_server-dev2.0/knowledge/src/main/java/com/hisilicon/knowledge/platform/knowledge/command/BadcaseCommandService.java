package com.hisilicon.knowledge.platform.knowledge.command;

import com.hisilicon.knowledge.platform.knowledge.domain.entity.BadcaseProblemType;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.BadcaseSource;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.BadcaseStatus;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.BadcaseTicket;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.BadcaseTicketId;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.IdGenerator;
import com.hisilicon.knowledge.platform.knowledge.domain.repository.BadcaseTicketRepository;
import com.hisilicon.knowledge.platform.knowledge.exception.BadcaseInvalidSourceTypeException;
import com.hisilicon.knowledge.platform.knowledge.exception.BadcaseInvalidStatusException;
import com.hisilicon.knowledge.platform.knowledge.exception.BadcaseNotFoundException;
import com.hisilicon.knowledge.platform.knowledge.infrastructure.repository.BadcaseHandlingPanacheRepository;
import com.hisilicon.knowledge.platform.knowledge.infrastructure.repository.po.BadcaseHandlingPO;
import io.quarkus.hibernate.reactive.panache.common.WithTransaction;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;

/**
 * Badcase 命令服务（应用服务层）：创建工单 + 统一处理（状态变更 + 备注）。
 */
@ApplicationScoped
public class BadcaseCommandService {

    private static final Logger logger = LoggerFactory.getLogger(BadcaseCommandService.class);

    private final BadcaseTicketRepository ticketRepository;
    private final BadcaseHandlingPanacheRepository handlingPanacheRepo;

    public BadcaseCommandService(BadcaseTicketRepository ticketRepository,
                                BadcaseHandlingPanacheRepository handlingPanacheRepo) {
        this.ticketRepository = ticketRepository;
        this.handlingPanacheRepo = handlingPanacheRepo;
    }

    /**
     * 创建 Badcase 工单。
     *
     * @param submittedBy 提交人账号
     * @param command     创建命令
     * @return 工单 ID
     */
    @WithTransaction
    public Uni<String> create(String submittedBy, CreateBadcaseCommand command) {
        BadcaseSource source = BadcaseSource.fromCode(command.source());
        BadcaseProblemType problemType = BadcaseProblemType.fromCode(command.problemType());
        if (source == null || !BadcaseProblemType.isValid(source, problemType)) {
            logger.warn("Invalid source+problemType combo: source={}, problemType={}, operator={}",
                    command.source(), command.problemType(), submittedBy);
            throw new BadcaseInvalidSourceTypeException("来源与问题类型组合不匹配");
        }
        BadcaseTicket ticket = BadcaseTicket.create(
                source, problemType, command.description(),
                command.kbId(), command.kbName(),
                command.relatedDocIds(), command.relatedDocNames(),
                command.sourcePayload(), submittedBy, null);
        return ticketRepository.persist(ticket)
                .map(t -> t.getId().value());
    }

    /**
     * 统一处理工单：修改目标状态 + 填写备注（写 handling 历史 + 冗余主表 latest_*）。
     *
     * @param handledBy  处理人账号
     * @param handledByName 处理人显示名
     * @param command    处理命令
     * @return 工单 ID
     */
    @WithTransaction
    public Uni<String> handle(String handledBy, String handledByName, HandleBadcaseCommand command) {
        BadcaseStatus targetStatus = BadcaseStatus.fromCode(command.targetStatus());
        if (targetStatus == null) {
            logger.warn("Invalid targetStatus: {}, operator={}", command.targetStatus(), handledBy);
            throw new BadcaseInvalidStatusException("非法的工单状态");
        }
        return findExisting(command.ticketId())
                .chain(ticket -> {
                    BadcaseTicket updated = ticket.changeStatus(targetStatus, command.remark(), handledBy, handledByName);
                    BadcaseHandlingPO handling = new BadcaseHandlingPO();
                    handling.id = IdGenerator.badcaseHandling();
                    handling.ticketId = updated.getId().value();
                    handling.action = targetStatus.name();
                    handling.remark = command.remark();
                    handling.handledBy = handledBy;
                    handling.handledByName = handledByName;
                    handling.handledAt = LocalDateTime.now();
                    return ticketRepository.merge(updated)
                            .chain(v -> handlingPanacheRepo.persist(handling))
                            .map(po -> updated.getId().value());
                });
    }

    /**
     * 移交处理人：只改主表 latest_handled_by / latest_handled_by_name（不改状态、不写 handling 历史）。
     */
    @WithTransaction
    public Uni<String> assign(AssignBadcaseCommand command) {
        if (command.handler() == null || command.handler().isBlank()) {
            throw new IllegalArgumentException("handler required");
        }
        return findExisting(command.ticketId())
                .chain(ticket -> {
                    BadcaseTicket updated = ticket.assignTo(
                            command.handler(), command.handlerName(), command.remark());
                    return ticketRepository.merge(updated)
                            .map(v -> updated.getId().value());
                });
    }

    private Uni<BadcaseTicket> findExisting(String id) {
        return ticketRepository.findById(BadcaseTicketId.of(id))
                .map(opt -> opt.orElseThrow(() -> {
                    logger.warn("Badcase ticket not found: id={}", id);
                    return new BadcaseNotFoundException("Badcase 工单不存在");
                }));
    }
}
