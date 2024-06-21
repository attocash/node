package atto.node.convertion

import org.springframework.core.convert.converter.Converter

interface DBConverter<S, T> : Converter<S, T>
