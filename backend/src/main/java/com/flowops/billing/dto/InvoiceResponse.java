package com.flowops.billing.dto;

import java.time.Instant;

public record InvoiceResponse(
        String id,
        Instant date,
        Long amountPaid,
        String currency,
        String status,
        String hostedInvoiceUrl,
        String pdfUrl
) {}
