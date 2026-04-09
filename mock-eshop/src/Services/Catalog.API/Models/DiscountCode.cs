// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License. See LICENSE in the project root.
// ---------------------------------------------------------------
//
// Catalog.API — Promotional discount entity. Persisted in the
// Promotions table via EF Core. Loaded by DiscountService at
// checkout time when a user enters a coupon code.
//
namespace Microsoft.eShop.Services.Catalog.API.Models;

/// <summary>
/// Promotional discount code applied at checkout.
///
/// Loaded from the <c>Promotions</c> table by
/// <see cref="Services.DiscountService"/> based on the user-supplied
/// <c>codeName</c>. May be <c>null</c> when the user enters an
/// unknown or expired code — callers must guard against null.
/// </summary>
public class DiscountCode
{
    /// <summary>The promotional code as the user types it (e.g. "SAVE10").</summary>
    public string Code { get; set; } = string.Empty;

    /// <summary>Percentage off (0-100) applied to the order subtotal.</summary>
    public decimal Percentage { get; set; }

    /// <summary>Whether the code is currently active in the campaigns table.</summary>
    public bool IsActive { get; set; }

    /// <summary>UTC timestamp after which the code is no longer valid.</summary>
    public DateTime ExpiresAt { get; set; }
}
