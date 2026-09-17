package com.rentalshop.backend.service.trail;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Active whenever app.redundant-trail.sheets.enabled isn't explicitly
 * true. See NoOpTelegramSender's javadoc -- same reasoning applies here:
 * a row's sheetsStatus starts and stays DISABLED when this channel is off,
 * so this bean exists only to give Spring something to inject.
 */
@Component
@ConditionalOnProperty(name = "app.redundant-trail.sheets.enabled", havingValue = "false", matchIfMissing = true)
public class NoOpSheetsAppender implements SheetsAppender {

    @Override
    public void appendRow(List<String> row) {
        // Deliberately does nothing -- see class javadoc.
    }
}
