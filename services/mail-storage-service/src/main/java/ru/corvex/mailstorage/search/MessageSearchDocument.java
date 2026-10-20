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

    public MessageSearchDocument() {
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
}
