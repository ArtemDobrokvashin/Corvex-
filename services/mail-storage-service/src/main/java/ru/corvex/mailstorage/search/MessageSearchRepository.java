package ru.corvex.mailstorage.search;

import org.springframework.data.elasticsearch.repository.ElasticsearchRepository;

public interface MessageSearchRepository extends ElasticsearchRepository<MessageSearchDocument, String> {
}
