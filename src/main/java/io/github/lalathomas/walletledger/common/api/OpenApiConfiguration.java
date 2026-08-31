package io.github.lalathomas.walletledger.common.api;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.headers.Header;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.Parameter;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class OpenApiConfiguration {

    @Bean
    OpenAPI walletLedgerOpenApi() {
        return new OpenAPI()
                .components(new Components())
                .info(new Info()
                        .title("Wallet Ledger API")
                        .version("1.0.0")
                        .description("Concurrency-safe integer-unit wallet operations, "
                                + "idempotent debit refunds, immutable history, and diagnostic "
                                + "balance reconciliation."));
    }

    @Bean
    OpenApiCustomizer correlationIdDocumentation() {
        return openApi -> openApi.getPaths().values().forEach(pathItem ->
                pathItem.readOperations().forEach(operation -> {
                    operation.addParametersItem(new Parameter()
                            .in("header")
                            .name(CorrelationIdFilter.HEADER_NAME)
                            .required(false)
                            .description("Optional caller correlation identifier. It must start "
                                    + "with an alphanumeric character, contain only letters, "
                                    + "numbers, '.', '_', ':' or '-', and be at most 100 "
                                    + "characters. Invalid values are replaced.")
                            .schema(new StringSchema().maxLength(100)));
                    operation.getResponses().values().forEach(response ->
                            response.addHeaderObject(
                                    CorrelationIdFilter.HEADER_NAME,
                                    new Header()
                                            .description("Accepted or generated request "
                                                    + "correlation identifier")
                                            .schema(new StringSchema().maxLength(100))
                            )
                    );
                })
        );
    }
}
