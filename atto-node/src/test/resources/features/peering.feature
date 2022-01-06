Feature: Peering

  Scenario: After start up should peer with default node
    Given the neighbour node A
    And is a default node

    When default handshake starts

    Then THIS node is A node peer

