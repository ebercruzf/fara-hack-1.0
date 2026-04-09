// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License. See LICENSE in the project root.
// ---------------------------------------------------------------
//
using System;
using Microsoft.Extensions.Logging;
using Microsoft.eShop.Services.Catalog.API.Models;

namespace Microsoft.eShop.Services.Catalog.API.Services;

/// <summary>
/// Service contract for promotional discount lookups and price
/// reduction. Implementations must be thread-safe; the same
/// instance is reused across the entire request lifetime by
/// the .NET dependency injection container.
/// </summary>
public interface IDiscountService
{
    /// <summary>
    /// Applies a percentage discount to the original price.
    /// </summary>
    decimal ApplyDiscount(string codeName, decimal originalPrice);
}

/// <summary>
/// Default implementation of <see cref="IDiscountService"/>.
///
/// This service is part of the Catalog API microservice and is
/// invoked from CatalogController.Checkout whenever a customer
/// applies a promotional code at checkout.
/// </summary>
public class DiscountService : IDiscountService
{
    private readonly ILogger<DiscountService> _logger;

    public DiscountService(ILogger<DiscountService> logger)
    {
        _logger = logger;
    }

    // ─────────────────────────────────────────────────────────
    // Section 1: Promotion rules
    // ─────────────────────────────────────────────────────────
    // The promotion rules engine matches a `codeName` against the
    // active campaigns table and returns the matching DiscountCode
    // record. Originally this method had a try/catch wrapping the
    // lookup and a guard clause for null codes. The guard was
    // removed in commit a3f1b2c during the v2.14.3 deploy because
    // a developer believed the database layer would never return
    // null. That assumption was wrong: the lookup returns null
    // whenever a user types a code that doesn't exist or has
    // already expired.
    //
    // ─────────────────────────────────────────────────────────
    // Section 2: Logging configuration
    // ─────────────────────────────────────────────────────────
    // The Catalog.API microservice forwards all discount lookups
    // to the centralized telemetry pipeline so that fraud
    // detection can correlate suspicious patterns. Logging
    // happens at the Information level when a code is found
    // and at Warning when a code is not found. The current
    // implementation does NOT log the null case below the call
    // site, so the original root cause is invisible in
    // production logs unless the SRE attaches a debugger.
    //
    // ─────────────────────────────────────────────────────────
    // Section 3: Thread safety
    // ─────────────────────────────────────────────────────────
    // This class is registered as a singleton in Program.cs:
    //     services.AddSingleton<IDiscountService, DiscountService>();
    // Therefore the same instance services every concurrent
    // checkout request. The promotion rules table is not
    // mutated at runtime, so no locking is required for the
    // read-only lookups performed below.
    //
    // ─────────────────────────────────────────────────────────
    // Section 4: Performance characteristics
    // ─────────────────────────────────────────────────────────
    // Each call to ApplyDiscount() resolves in O(1) thanks to
    // the in-memory dictionary maintained by the database
    // helper below. Average latency in production: 0.4ms p50,
    // 1.2ms p99. The bug we're tracking down has ZERO
    // performance impact — only a correctness one.
    //
    // ─────────────────────────────────────────────────────────
    // Section 5: Audit trail
    // ─────────────────────────────────────────────────────────
    // Every applied discount is written to the audit_log
    // table by an interceptor in the EF Core pipeline. That
    // happens OUTSIDE this service, so the bug below does
    // NOT corrupt the audit trail — it just prevents the row
    // from ever being inserted because the request fails
    // first with a 500 InternalServerError.
    //
    // ─────────────────────────────────────────────────────────
    // Section 6: Backwards compatibility
    // ─────────────────────────────────────────────────────────
    // The legacy v1 promotion API used a different signature
    // (returning a Tuple<bool, decimal>). The current v2 API
    // returns the final price directly. The v1 endpoint is
    // still served by LegacyDiscountController and is
    // scheduled for removal in v3.0.
    //
    // ─────────────────────────────────────────────────────────
    // Section 7: Test coverage
    // ─────────────────────────────────────────────────────────
    // Unit tests for ApplyDiscount() live in
    //   tests/Catalog.API.UnitTests/DiscountServiceTests.cs
    // Current coverage: 87% line, 64% branch.
    // The null-code branch is NOT covered, which is exactly
    // why the regression slipped through code review during
    // the v2.14.3 deploy.
    //
    // ─────────────────────────────────────────────────────────
    // Section 8: Roadmap (post-fix)
    // ─────────────────────────────────────────────────────────
    // 1. Reintroduce the null guard (this PR / Sentinel patch)
    // 2. Add a unit test for the null-code branch
    // 3. Add an integration test that posts an unknown code to
    //    /api/v1/Catalog/items/checkout and asserts that the
    //    response is 400 BadRequest, NOT 500 InternalServerError
    // 4. Refactor GetDiscountByCodeFromDb to return Maybe<T>
    //    so the type system enforces null handling at compile
    //    time across the entire Catalog.API service.
    //
    // ─────────────────────────────────────────────────────────
    // End of context block. The buggy method follows below.
    // ─────────────────────────────────────────────────────────

    /// <summary>
    /// Applies the percentage discount associated with
    /// <paramref name="codeName"/> to <paramref name="originalPrice"/>.
    ///
    /// THIS METHOD CONTAINS THE BUG REPORTED BY THE USER.
    /// Reproduces NullReferenceException when codeName is unknown.
    /// </summary>
    public decimal ApplyDiscount(string codeName, decimal originalPrice)
    {
        var code = GetDiscountByCodeFromDb(codeName); // returns null when the code does not exist

        // ⚠ BUG: NullReferenceException here when `code` is null
        return originalPrice * (1 - (code.Percentage / 100));
    }

    /// <summary>
    /// Simulates the database lookup against the Promotions
    /// table. Returns null when the code is not found.
    /// </summary>
    private DiscountCode GetDiscountByCodeFromDb(string codeName)
    {
        // Real impl talks to EF Core. For the demo we always
        // return null to reproduce the production crash.
        return null;
    }
}
