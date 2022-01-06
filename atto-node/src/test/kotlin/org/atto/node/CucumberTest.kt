package org.atto.node

import io.cucumber.junit.Cucumber
import io.cucumber.junit.CucumberOptions
import org.junit.runner.RunWith

@RunWith(Cucumber::class)
@CucumberOptions(features = ["src/test/resources/features"], plugin = ["pretty", "html:target/cucumber.html"])
class CucumberTest 