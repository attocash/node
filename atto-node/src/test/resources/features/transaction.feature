Feature: Transaction


  Scenario: Transaction should be confirmed
    Given the peer A

    When send transaction 1 from THIS account to A account

    Then transaction 1 is confirmed
    And  matching open or receive transaction for transaction 1 is confirmed

  Scenario: Multiple transactions should be confirmed even when THIS node does NOT have voting weight anymore
    Given the peer A

    When send transaction 1 from THIS account to A account
    Then transaction 1 is confirmed
    And  matching open or receive transaction for transaction 1 is confirmed

    When send transaction 2 from THIS account to A account
    Then transaction 2 is confirmed
    And  matching open or receive transaction for transaction 2 is confirmed

    When send transaction 3 from THIS account to A account
    Then transaction 3 is confirmed
    And  matching open or receive transaction for transaction 3 is confirmed

    When send transaction 4 from THIS account to A account
    Then transaction 4 is confirmed
    And  matching open or receive transaction for transaction 4 is confirmed

    When send transaction 5 from A account to THIS account
    Then transaction 5 is confirmed
    And  matching open or receive transaction for transaction 5 is confirmed

  Scenario: Votes should still be casted after change of representative
    Given the peer A

    When change transaction 1 from THIS account to A representative
    Then transaction 1 is confirmed for THIS peer
    Then transaction 1 is confirmed for A peer

    When send transaction 2 from THIS account to A account
    Then transaction 2 is confirmed
    And  matching open or receive transaction for transaction 2 is confirmed