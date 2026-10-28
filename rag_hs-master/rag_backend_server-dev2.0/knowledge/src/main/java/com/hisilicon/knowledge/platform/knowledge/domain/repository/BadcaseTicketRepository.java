package com.hisilicon.knowledge.platform.knowledge.domain.repository;

import com.hisilicon.knowledge.platform.knowledge.domain.entity.BadcaseTicket;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.BadcaseTicketId;
import io.smallrye.mutiny.Uni;

import java.util.Optional;

/**
 * Badcase 工单仓储接口（领域层）。
 */
public interface BadcaseTicketRepository {

    Uni<Optional<BadcaseTicket>> findById(BadcaseTicketId id);

    Uni<BadcaseTicket> persist(BadcaseTicket ticket);

    Uni<BadcaseTicket> merge(BadcaseTicket ticket);
}
