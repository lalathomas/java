package io.github.lalathomas.walletledger.wallet.api;

import io.github.lalathomas.walletledger.common.api.ApiErrorResponse;
import io.github.lalathomas.walletledger.wallet.application.MoneyMovementResult;
import io.github.lalathomas.walletledger.wallet.application.ReconciliationResult;
import io.github.lalathomas.walletledger.wallet.application.WalletService;
import io.github.lalathomas.walletledger.wallet.application.WalletSnapshot;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping(path = "/api/v1/wallets", produces = "application/json")
@Tag(name = "Wallets", description = "Concurrency-safe wallet and immutable ledger operations")
public class WalletController {

    static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";
    static final String IDEMPOTENT_REPLAYED_HEADER = "Idempotent-Replayed";
    private static final String IDEMPOTENCY_DESCRIPTION =
            "Required wallet-scoped request key. Reusing it with the exact same request replays "
                    + "the original result; reusing it for another request returns 409.";

    private final WalletService walletService;

    public WalletController(WalletService walletService) {
        this.walletService = walletService;
    }

    @PostMapping
    @Operation(summary = "Create a zero-balance wallet")
    @ApiResponse(
            responseCode = "201",
            description = "Wallet created with a zero balance",
            headers = @Header(
                    name = HttpHeaders.LOCATION,
                    description = "URI of the created wallet balance resource",
                    schema = @Schema(type = "string", format = "uri")
            ),
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = WalletResponse.class)
            )
    )
    public ResponseEntity<WalletResponse> createWallet(
            @Valid @RequestBody CreateWalletRequest request
    ) {
        WalletSnapshot wallet = walletService.createWallet(request.playerId());
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{playerId}/balance")
                .buildAndExpand(wallet.playerId())
                .toUri();
        return ResponseEntity.created(location).body(WalletResponse.from(wallet));
    }

    @PostMapping("/{playerId}/credits")
    @Operation(summary = "Credit integer units to a wallet")
    @ApiResponse(
            responseCode = "200",
            description = "Credit applied, or the exact request replayed",
            headers = @Header(
                    name = IDEMPOTENT_REPLAYED_HEADER,
                    description = "True when the original idempotent result was replayed",
                    schema = @Schema(type = "boolean")
            ),
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = MoneyMovementResponse.class)
            )
    )
    public ResponseEntity<MoneyMovementResponse> credit(
            @PathVariable UUID playerId,
            @Parameter(description = IDEMPOTENCY_DESCRIPTION, required = true)
            @RequestHeader(IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
            @Valid @RequestBody MoneyMovementRequest request
    ) {
        MoneyMovementResult result = walletService.credit(
                playerId,
                request.toCommand(idempotencyKey)
        );
        return movementResponse(result);
    }

    @PostMapping("/{playerId}/debits")
    @Operation(summary = "Debit integer units without permitting a negative balance")
    @ApiResponse(
            responseCode = "200",
            description = "Debit applied, or the exact request replayed",
            headers = @Header(
                    name = IDEMPOTENT_REPLAYED_HEADER,
                    description = "True when the original idempotent result was replayed",
                    schema = @Schema(type = "boolean")
            ),
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = MoneyMovementResponse.class)
            )
    )
    public ResponseEntity<MoneyMovementResponse> debit(
            @PathVariable UUID playerId,
            @Parameter(description = IDEMPOTENCY_DESCRIPTION, required = true)
            @RequestHeader(IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
            @Valid @RequestBody MoneyMovementRequest request
    ) {
        MoneyMovementResult result = walletService.debit(
                playerId,
                request.toCommand(idempotencyKey)
        );
        return movementResponse(result);
    }

    @PostMapping("/{playerId}/transactions/{debitTransactionId}/refunds")
    @Operation(
            summary = "Refund a debit in full",
            description = "Creates one linked CREDIT whose amount is derived from the original "
                    + "DEBIT. A debit cannot be refunded more than once."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Refund created, or the exact request replayed",
                    headers = @Header(
                            name = IDEMPOTENT_REPLAYED_HEADER,
                            description = "True when the original idempotent result was replayed",
                            schema = @Schema(type = "boolean")
                    ),
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = MoneyMovementResponse.class)
                    )
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Malformed or invalid request, path value, or idempotency key",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ApiErrorResponse.class)
                    )
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "Wallet or wallet-owned debit transaction not found",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ApiErrorResponse.class)
                    )
            ),
            @ApiResponse(
                    responseCode = "409",
                    description = "Idempotency conflict or debit already refunded",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ApiErrorResponse.class)
                    )
            ),
            @ApiResponse(
                    responseCode = "422",
                    description = "Target is not a debit or refund would overflow the balance",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ApiErrorResponse.class)
                    )
            ),
            @ApiResponse(
                    responseCode = "503",
                    description = "Wallet lock could not be acquired; retry with the same key",
                    headers = @Header(
                            name = HttpHeaders.RETRY_AFTER,
                            description = "Seconds before retrying",
                            schema = @Schema(type = "integer", format = "int32", example = "1")
                    ),
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ApiErrorResponse.class)
                    )
            )
    })
    public ResponseEntity<MoneyMovementResponse> refund(
            @PathVariable UUID playerId,
            @PathVariable UUID debitTransactionId,
            @Parameter(description = IDEMPOTENCY_DESCRIPTION, required = true)
            @RequestHeader(IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
            @Valid @RequestBody RefundRequest request
    ) {
        MoneyMovementResult result = walletService.refund(
                playerId,
                debitTransactionId,
                request.toCommand(idempotencyKey)
        );
        return movementResponse(result);
    }

    @GetMapping("/{playerId}/balance")
    @Operation(summary = "Read the current materialized wallet balance")
    public BalanceResponse getBalance(@PathVariable UUID playerId) {
        return BalanceResponse.from(walletService.getBalance(playerId));
    }

    @GetMapping("/{playerId}/reconciliation")
    @Operation(
            summary = "Compare the stored balance with the signed ledger total",
            description = "Diagnostic read only. It reports inconsistencies but never repairs "
                    + "or mutates wallet data."
    )
    public ReconciliationResponse reconcile(@PathVariable UUID playerId) {
        ReconciliationResult result = walletService.reconcile(playerId);
        return ReconciliationResponse.from(result);
    }

    @GetMapping("/{playerId}/transactions")
    @Operation(summary = "Read deterministic, newest-first immutable ledger history")
    public TransactionHistoryResponse getHistory(
            @PathVariable UUID playerId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return TransactionHistoryResponse.from(walletService.getHistory(playerId, page, size));
    }

    private static ResponseEntity<MoneyMovementResponse> movementResponse(
            MoneyMovementResult result
    ) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(IDEMPOTENT_REPLAYED_HEADER, Boolean.toString(result.replayed()));
        return new ResponseEntity<>(MoneyMovementResponse.from(result), headers, HttpStatus.OK);
    }
}
