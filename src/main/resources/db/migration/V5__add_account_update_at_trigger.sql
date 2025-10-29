CREATE TRIGGER account_set_updated_at
  BEFORE UPDATE ON account
  FOR EACH ROW
  SET NEW.updated_at = CURRENT_TIMESTAMP(3);
