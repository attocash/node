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