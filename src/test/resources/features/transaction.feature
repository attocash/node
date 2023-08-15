Feature: Transaction

  Scenario: Transaction should be confirmed
    Given the peer A

    When send transaction 1 from THIS account to A account
    When transaction 1 is confirmed
    When receive transaction 2 from 1 send transaction to A account

    Then  transaction 2 is confirmed

  Scenario: Multiple transactions should be confirmed even when THIS node does NOT have voting weight anymore
    Given the peer A

    When send transaction S1 from THIS account to A account
    And transaction S1 is confirmed
    And receive transaction R1 from S1 send transaction to A account
    And  transaction R1 is confirmed

    When send transaction S2 from THIS account to A account
    And transaction S2 is confirmed
    And receive transaction R2 from S2 send transaction to A account
    And transaction R2 is confirmed

    When send transaction S3 from THIS account to A account
    And transaction S3 is confirmed
    And receive transaction R3 from S3 send transaction to A account
    And transaction R3 is confirmed

    When send transaction S4 from THIS account to A account
    And transaction S4 is confirmed
    And receive transaction R4 from S4 send transaction to A account
    And  transaction R4 is confirmed

    When send transaction S5 from A account to THIS account

    Then transaction S5 is confirmed

  Scenario: Votes should still be casted after change of representative
    Given the peer A

    When change transaction 1 from THIS account to A representative
    And transaction 1 is confirmed

    When send transaction 2 from THIS account to A account

    Then transaction 2 is confirmed