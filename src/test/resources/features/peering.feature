Feature: Peering

  Scenario Outline: Peers stay connected and exchange transactions after reconnecting
    Given the peer A connected by <initiator>
    Then peer A stays connected beyond the handshake deadline

    When send transaction 1 from THIS account to A account
    Then transaction 1 is confirmed

    When peer A reconnects after its connection is closed
    Then THIS node is A node peer
    And A node is THIS node peer

    When receive transaction 2 from 1 send transaction to A account
    Then transaction 2 is confirmed

    Examples:
      | initiator |
      | THIS      |
      | A         |
