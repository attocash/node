Feature: Bootstrap

  Scenario: Missing transaction should be requested when missing
    Given the peer A

    And send transaction 1 from THIS account to A account
    And transaction 1 is confirmed

    And the peer B

    And receive transaction 2 from 1 send transaction to A account

    When peer B finds 2 unchecked transactions
    And peer B unchecked transactions are processed

    Then transaction 2 is confirmed

  Scenario: Missing transaction should be requested when gap exists
    Given the peer A

    And send transaction 1 from THIS account to A account
    And transaction 1 is confirmed

    And receive transaction 2 from 1 send transaction to A account
    And transaction 2 is confirmed

    And the peer B

    And send transaction 3 from A account to THIS account

    When peer B finds 1 unchecked transactions
    And peer B look for missing transactions
    And peer B finds 3 unchecked transactions
    And peer B unchecked transactions are processed

    Then transaction 1 is confirmed
    Then transaction 2 is confirmed
    Then transaction 3 is confirmed

  Scenario: Last transaction should be broadcast
    Given the peer A

    And send transaction 1 from THIS account to A account
    And transaction 1 is confirmed

    And the peer B

    When peer THIS broadcast last sample
    And peer B finds 1 unchecked transactions
    And peer B unchecked transactions are processed

    Then transaction 1 is confirmed

