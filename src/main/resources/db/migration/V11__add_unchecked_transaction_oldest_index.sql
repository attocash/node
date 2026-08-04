CREATE INDEX unchecked_transaction_oldest
  ON unchecked_transaction (`timestamp`, public_key, height);
