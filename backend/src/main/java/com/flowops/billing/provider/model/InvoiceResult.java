package com.flowops.billing.provider.model;

import java.time.Instant;

public record InvoiceResult(
        String invoiceId,
        String customerId,
        Long amountPaid,
        String currency,
        String status,
        Instant paidAt,
        String hostedInvoiceUrl,
        String pdfUrl
) {}
