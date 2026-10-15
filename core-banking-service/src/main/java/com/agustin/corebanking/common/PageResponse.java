package com.agustin.corebanking.common;

import java.util.List;
import java.util.function.Function;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

/** Paginación de docs/05 §5: {@code {content, page, size, totalElements, totalPages}}. */
public record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages) {

	public static final int MAX_SIZE = 100;

	public static <S, T> PageResponse<T> of(Page<S> page, Function<S, T> mapper) {
		return new PageResponse<>(page.getContent().stream().map(mapper).toList(), page.getNumber(),
				page.getSize(), page.getTotalElements(), page.getTotalPages());
	}

	/** Normaliza {@code page >= 0} y {@code 1 <= size <= 100}. */
	public static Pageable pageable(int page, int size) {
		return PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_SIZE));
	}

}
