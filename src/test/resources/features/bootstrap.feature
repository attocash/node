Feature: Bootstrap

  Scenario: Missing transaction should be requested when missing
    Given the peer A

    And send transaction 1 from THIS account to A account
    And transaction 1 is confirmed

    When the peer B
    And receive transaction 2 from 1 send transaction to A account

    Then transaction 1 is confirmed
    Then transaction 2 is confirmed

  Scenario: Missing transaction should be requested when gap exists
    Given the peer A

    And send transaction 1 from THIS account to A account
    And transaction 1 is confirmed

    And receive transaction 2 from 1 send transaction to A account
    And transaction 2 is confirmed

    When the peer B
    And send transaction 3 from A account to THIS account

    Then transaction 1 is confirmed
    Then transaction 2 is confirmed
    Then transaction 3 is confirmed

  Scenario: Last transaction should be broadcast
    Given the peer A

    And send transaction 1 from THIS account to A account
    And transaction 1 is confirmed

    And the peer B

    When transaction 1 missing votes are grabbed
    When peer THIS broadcast last sample

    Then transaction 1 is confirmed

  Scenario: Missing votes are grabbed
    Given the peer A

    And send transaction 1 from THIS account to A account
    And transaction 1 is confirmed

    And receive transaction 2 from 1 send transaction to A account
    And transaction 2 is confirmed

    And send transaction 3 from THIS account to A account
    And transaction 3 is confirmed

    And receive transaction 4 from 3 send transaction to A account
    And transaction 4 is confirmed

    When the peer B
    When transaction 3 missing votes are grabbed
    When transaction 4 missing votes are grabbed
    When peer THIS broadcast last sample

    Then transaction 1 is confirmed
    Then transaction 2 is confirmed
    Then transaction 3 is confirmed

