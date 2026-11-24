package ru.corvex.mailstorage.search;

import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.DateFormat;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;

import java.time.Instant;
import java.util.List;

/** Производный поисковый индекс: источник истины — PostgreSQL + Maildir, индекс перестраивается из них. */
@Document(indexName = "corvex-messages")
public class MessageSearchDocument {

    @Id
    private String id;

    @Field(type = FieldType.Keyword)
    private String ownerId;

    @Field(type = FieldType.Keyword)
    private String folderId;

    @Field(type = FieldType.Long)
    private long uid;

    @Field(type = FieldType.Text)
    private String sender;

    @Field(type = FieldType.Text)
    private List<String> recipients;

    @Field(type = FieldType.Text)
    private String subject;

    @Field(type = FieldType.Text)
    private String body;

    @Field(type = FieldType.Date, format = DateFormat.date_time)
    private Instant internalDate;

    @Field(type = FieldType.Boolean)
    private boolean hasAttachments;

    /** Флаг «прочитано» на момент индексации: нужен фильтру `is:unread`. */
    @Field(type = FieldType.Boolean)
    private boolean seen;

    public MessageSearchDocument() {
    }

    public boolean isSeen() {
        return seen;
    }

    public MessageSearchDocument(String id, String ownerId, String folderId, long uid, String sender,
                                 List<String> recipients, String subject, String body, Instant internalDate,
                                 boolean hasAttachments) {
        this.id = id;
        this.ownerId = ownerId;
        this.folderId = folderId;
        this.uid = uid;
        this.sender = sender;
        this.recipients = recipients;
        this.subject = subject;
        this.body = body;
        this.internalDate = internalDate;
        this.hasAttachments = hasAttachments;
    }

    public MessageSearchDocument withSeen(boolean seen) {
        this.seen = seen;
        return this;
    }

    public String getId() { return id; }
    public String getOwnerId() { return ownerId; }
    public String getFolderId() { return folderId; }
    public long getUid() { return uid; }
    public String getSender() { return sender; }
    public java.util.List<String> getRecipients() { return recipients; }
    public String getSubject() { return subject; }
    public String getBody() { return body; }
    public java.time.Instant getInternalDate() { return internalDate; }
    public boolean isHasAttachments() { return hasAttachments; }
}
