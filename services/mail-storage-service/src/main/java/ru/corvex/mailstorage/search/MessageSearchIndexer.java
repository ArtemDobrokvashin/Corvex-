package ru.corvex.mailstorage.search;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import ru.corvex.mailstorage.service.MailDeliveryService;

import java.util.ArrayList;
import java.util.List;

/** Индексация после коммита доставки; недоступность Elasticsearch не должна терять письмо. */
@Component
public class MessageSearchIndexer {

    private static final Logger log = LoggerFactory.getLogger(MessageSearchIndexer.class);

    private final MessageSearchRepository search;

    public MessageSearchIndexer(MessageSearchRepository search) {
        this.search = search;
    }

    public void index(MailDeliveryService.DeliveryResult result) {
        List<String> recipients = new ArrayList<>();
        result.parsed().to().forEach(a -> recipients.add(a.address()));
        result.parsed().cc().forEach(a -> recipients.add(a.address()));
        if (!recipients.contains(result.mailbox())) {
            recipients.add(result.mailbox());
        }
        MessageSearchDocument doc = new MessageSearchDocument(
                result.messageId().toString(),
                result.ownerId().toString(),
                result.folderId().toString(),
                result.uid(),
                result.parsed().sender(),
                recipients,
                result.parsed().subject(),
                result.parsed().bodyText(),
                result.parsed().internalDate(),
                !result.parsed().attachments().isEmpty());
        try {
            search.save(doc);
        } catch (RuntimeException e) {
            // По архитектуре ES — производные данные: письмо уже в Maildir/PostgreSQL,
            // поиск временно отстаёт и лечится переиндексацией, а не потерей доставки.
            log.warn("Elasticsearch indexing failed for message {}: {}", result.messageId(), e.getMessage());
        }
    }
}
