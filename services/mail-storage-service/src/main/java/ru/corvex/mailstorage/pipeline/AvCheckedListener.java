package ru.corvex.mailstorage.pipeline;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import ru.corvex.mailstorage.search.MessageSearchIndexer;
import ru.corvex.mailstorage.service.MailDeliveryService;
import ru.corvex.mailstorage.storage.RawMessageSpool;

import java.io.IOException;
import java.time.Instant;
import java.util.Optional;

/** Потребитель mail.av.checked: доставка по каждому получателю, затем индексация и mail.delivered. */
@Component
public class AvCheckedListener {

    private static final Logger log = LoggerFactory.getLogger(AvCheckedListener.class);

    private final ObjectMapper mapper;
    private final MailDeliveryService delivery;
    private final MessageSearchIndexer indexer;
    private final RabbitTemplate rabbit;
    private final RawMessageSpool spool;

    public AvCheckedListener(ObjectMapper mapper,
                             MailDeliveryService delivery,
                             MessageSearchIndexer indexer,
                             RabbitTemplate rabbit,
                             RawMessageSpool spool) {
        this.mapper = mapper;
        this.delivery = delivery;
        this.indexer = indexer;
        this.rabbit = rabbit;
        this.spool = spool;
    }

    @RabbitListener(queues = StoragePipelineAmqpConfig.QUEUE, containerFactory = "mailListenerFactory")
    public void onAvChecked(String payload) throws IOException {
        MailPipelineEvents.AvCheckedEvent event = mapper.readValue(payload, MailPipelineEvents.AvCheckedEvent.class);
        for (String recipient : event.recipients()) {
            Optional<MailDeliveryService.DeliveryResult> result = delivery.deliver(event, recipient);
            result.ifPresent(r -> {
                indexer.index(r);
                publishDelivered(event, r);
            });
        }
        try {
            spool.delete(event.rawRef());
        } catch (IOException e) {
            // Письмо уже скопировано в Maildir получателей; осиротевший spool безопаснее,
            // чем повторная доставка из-за ошибки удаления временного файла.
            log.warn("Не удалось удалить spool {} после доставки: {}", event.rawRef(), e.getMessage());
        }
    }

    private void publishDelivered(MailPipelineEvents.AvCheckedEvent event, MailDeliveryService.DeliveryResult r) {
        try {
            var delivered = new MailPipelineEvents.DeliveredEvent(
                    event.rawRef(), r.messageId(), r.ownerId(), r.mailbox(), r.folderId(), r.uid(), Instant.now());
            rabbit.convertAndSend(StoragePipelineAmqpConfig.EXCHANGE, "mail.delivered", mapper.writeValueAsString(delivered));
        } catch (JsonProcessingException | RuntimeException e) {
            log.warn("mail.delivered не опубликован для {}: {}", r.messageId(), e.getMessage());
        }
    }
}
