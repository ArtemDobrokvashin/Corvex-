-- Synthetic records only in the disposable corvex_fixture database.
INSERT INTO mail_folders (id, owner_id, name, special_use) VALUES
('6ce1ef07-1b52-49b7-b4da-064d26489321', 'a8e7caea-0c26-4d84-91ad-5e208d62b48c', 'Inbox', 'INBOX');
INSERT INTO message_metadata (id, owner_id, folder_id, uid, sender, size_bytes, maildir_path, seen, av_status) VALUES
('f61b2fbc-7419-4eb0-8992-35841d0c77af','a8e7caea-0c26-4d84-91ad-5e208d62b48c','6ce1ef07-1b52-49b7-b4da-064d26489321',1,'fixture@example.test',19,'fixture.eml',true,'PENDING');
