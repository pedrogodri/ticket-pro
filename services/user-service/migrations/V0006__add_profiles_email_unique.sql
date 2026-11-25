-- One profile per e-mail (case-insensitive). A UserRegistered whose e-mail already belongs to another
-- user_id is rejected with UserCreationFailed(DUPLICATE_PROFILE), and auth-service compensates.
CREATE UNIQUE INDEX profiles_email_lower_key ON profiles (lower(email));
