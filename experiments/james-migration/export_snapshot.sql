-- Experimental, read-only PostgreSQL metadata export for an OFFLINE COPY only.
-- Run with psql -X -qAt -v ON_ERROR_STOP=1; treat output as sensitive.
-- This transaction covers PostgreSQL only, not Maildir or other service DBs.
BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;
SELECT json_build_object('type','header', 'version',1, 'snapshot_id',pg_current_snapshot()::text)::text;
SELECT json_build_object('type','folder','id',id,'owner_id',owner_id,
       'parent_id',parent_id,'name',name,'special_use',special_use)::text
  FROM mail_folders ORDER BY id;
SELECT json_build_object('type','message','id',id,'owner_id',owner_id,'folder_id',folder_id,
       'path',maildir_path,'size_bytes',size_bytes,'seen',seen,'answered',answered,
       'flagged',flagged,'draft',draft,'deleted',deleted,'internal_date',internal_date,
       'av_status',av_status)::text
  FROM message_metadata ORDER BY id;
SELECT json_build_object('type','summary',
       'folders',(SELECT count(*) FROM mail_folders),
       'messages',(SELECT count(*) FROM message_metadata),
       'owner_mismatches',(SELECT count(*) FROM message_metadata m JOIN mail_folders f ON f.id=m.folder_id WHERE m.owner_id<>f.owner_id))::text;
COMMIT;
