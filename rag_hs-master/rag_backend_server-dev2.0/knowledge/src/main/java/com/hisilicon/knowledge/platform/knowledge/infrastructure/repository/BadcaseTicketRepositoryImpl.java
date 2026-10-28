package com.hisilicon.knowledge.platform.knowledge.infrastructure.repository;

import com.hisilicon.knowledge.platform.knowledge.domain.entity.BadcaseTicket;
import com.hisilicon.knowledge.platform.knowledge.domain.entity.BadcaseTicketId;
import com.hisilicon.knowledge.platform.knowledge.domain.repository.BadcaseTicketRepository;
import com.hisilicon.knowledge.platform.knowledge.infrastructure.mapper.BadcaseTicketMapper;
import com.hisilicon.knowledge.platform.knowledge.infrastructure.repository.po.BadcaseTicketPO;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Optional;

/**
 * Badcase 工单仓储实现。
 */
@ApplicationScoped
public class BadcaseTicketRepositoryImpl implements BadcaseTicketRepository {

    private final BadcaseTicketPanacheRepository panacheRepo;
    private final BadcaseTicketMapper mapper;

    @Inject
    public BadcaseTicketRepositoryImpl(BadcaseTicketPanacheRepository panacheRepo,
                                       BadcaseTicketMapper mapper) {
        this.panacheRepo = panacheRepo;
        this.mapper = mapper;
    }

    @Override
    public Uni<Optional<BadcaseTicket>> findById(BadcaseTicketId id) {
        return panacheRepo.findById(id.value())
                .map(po -> Optional.ofNullable(po).map(mapper::toDomain));
    }

    @Override
    public Uni<BadcaseTicket> persist(BadcaseTicket ticket) {
        return panacheRepo.persist(mapper.toPO(ticket))
                .map(po -> ticket);
    }

    @Override
    public Uni<BadcaseTicket> merge(BadcaseTicket ticket) {
        return panacheRepo.getSession()
                .flatMap(session -> session.merge(mapper.toPO(ticket)))
                .map(po -> ticket);
    }
}
