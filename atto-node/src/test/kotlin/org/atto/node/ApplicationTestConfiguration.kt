package org.atto.node

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter
import org.springframework.web.client.RestTemplate


@Configuration
class ApplicationTestConfiguration {
    @Bean
    fun restTemplate(objectMapper: ObjectMapper): RestTemplate {
        return RestTemplate(listOf(MappingJackson2HttpMessageConverter(objectMapper)))
    }
}