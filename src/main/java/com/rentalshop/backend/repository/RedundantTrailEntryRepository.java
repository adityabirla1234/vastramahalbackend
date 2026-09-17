package com.rentalshop.backend.repository;

import com.rentalshop.backend.entity.RedundantTrailEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface RedundantTrailEntryRepository extends JpaRepository<RedundantTrailEntry, Long> {

    /**
     * Ids of every row with at least one channel still in PENDING state --
     * i.e. never attempted, or a prior attempt failed with retries still
     * available. Used by RedundantTrailService's scheduled sweep to catch
     * anything the post-commit async delivery missed (app restarted
     * between commit and the async task running, Telegram/Sheets was down
     * at delivery time, etc.). Selects only the id, not the whole entity,
     * since the sweep re-loads each row fresh inside its own delivery call.
     */
    @Query("SELECT e.id FROM RedundantTrailEntry e WHERE e.telegramStatus = 'PENDING' OR e.sheetsStatus = 'PENDING'")
    List<Long> findIdsWithAnyPendingChannel();

    long countByTelegramStatus(RedundantTrailEntry.DeliveryStatus status);

    long countBySheetsStatus(RedundantTrailEntry.DeliveryStatus status);
}
