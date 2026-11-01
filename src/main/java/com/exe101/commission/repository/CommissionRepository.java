package com.exe101.commission.repository;

import com.exe101.entity.Commission;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CommissionRepository extends JpaRepository<Commission, Long> {
    // Commissions the User requested as a Customer or receives as the commissioned Artist, newest first.
    @Query("""
        select c from Commission c
        where c.customerId = :userId
           or c.artistId in (select a.artistId from ArtistProfile a where a.userId = :userId)
        order by c.createdAt desc, c.commissionId desc
        """)
    List<Commission> findForParticipant(@Param("userId") long userId);

    // UPDLOCK until commit: attachments are counted and added one at a time per Commission.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Commission> findWithLockByCommissionId(Long commissionId);

    // Shared lock until commit: a message is appended against a status that cannot change before it commits.
    @Lock(LockModeType.PESSIMISTIC_READ)
    Optional<Commission> findWithSharedLockByCommissionId(Long commissionId);
}
