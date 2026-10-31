package com.exe101.common.error;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum AppErrorMessage {
    EMAIL_ALREADY_REGISTERED(HttpStatus.CONFLICT, "Email is already registered."),
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "Invalid credentials or account unavailable."),
    AUTHENTICATION_REQUIRED(HttpStatus.UNAUTHORIZED, "A valid access token is required."),
    ACCESS_DENIED(HttpStatus.FORBIDDEN, "Access is denied."),
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "User was not found."),
    USER_NOT_ACTIVE(HttpStatus.CONFLICT, "User account is not active."),
    ARTIST_ALREADY_GRANTED(HttpStatus.CONFLICT, "User already has an Artist Profile."),
    PROVINCE_INVALID(HttpStatus.BAD_REQUEST, "Province code or name is invalid."),
    PROVINCE_NOT_FOUND(HttpStatus.NOT_FOUND, "Province was not found."),
    PROVINCE_ALREADY_EXISTS(HttpStatus.CONFLICT, "Province code already exists."),
    PROVINCE_UNKNOWN(HttpStatus.UNPROCESSABLE_CONTENT, "Province code is not a known delivery area."),
    PROVINCE_NOT_SUPPORTED(HttpStatus.UNPROCESSABLE_CONTENT, "Delivery to this province is not supported."),
    ADDRESS_INVALID(HttpStatus.BAD_REQUEST, "Address fields are invalid."),
    ADDRESS_NOT_FOUND(HttpStatus.NOT_FOUND, "Address was not found."),
    DEFAULT_ADDRESS_CONFLICT(HttpStatus.CONFLICT, "Default address was changed concurrently; retry."),
    ARTIST_PROFILE_REQUIRED(HttpStatus.FORBIDDEN, "An Artist Profile is required."),
    ARTIST_NOT_ACTIVE(HttpStatus.CONFLICT, "Artist Profile is not active."),
    TAG_INVALID(HttpStatus.BAD_REQUEST, "Tag name is invalid."),
    TAG_NOT_FOUND(HttpStatus.NOT_FOUND, "Tag was not found."),
    TAG_ALREADY_EXISTS(HttpStatus.CONFLICT, "Tag name already exists."),
    TAG_NOT_AVAILABLE(HttpStatus.UNPROCESSABLE_CONTENT, "A selected tag does not exist or is inactive."),
    ARTWORK_INVALID(HttpStatus.BAD_REQUEST, "Artwork fields are invalid."),
    ARTWORK_NOT_FOUND(HttpStatus.NOT_FOUND, "Artwork was not found."),
    ARTWORK_VERSION_CONFLICT(HttpStatus.CONFLICT, "Artwork was changed by another request; reload and retry."),
    ARTWORK_NOT_EDITABLE(HttpStatus.CONFLICT, "Moderated or archived Artwork cannot be changed."),
    ARTWORK_STATUS_CHANGE_NOT_ALLOWED(HttpStatus.CONFLICT, "This Artwork status change is not allowed."),
    ARTWORK_DIMENSION_LOCKED(HttpStatus.CONFLICT, "The 2D/3D class cannot change after first publication."),
    ARTWORK_PREVIEW_REQUIRED(HttpStatus.CONFLICT, "Published Artwork needs at least one preview."),
    MODERATION_REASON_REQUIRED(HttpStatus.BAD_REQUEST, "A reason is required for moderation."),
    PREVIEW_TYPE_NOT_SUPPORTED(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Previews must be JPEG, PNG or WebP images."),
    PREVIEW_TOO_LARGE(HttpStatus.CONTENT_TOO_LARGE, "Preview exceeds the maximum size."),
    PREVIEW_LIMIT_REACHED(HttpStatus.CONFLICT, "Artwork already has the maximum number of previews."),
    ARTWORK_MEDIA_NOT_FOUND(HttpStatus.NOT_FOUND, "Preview was not found."),
    ARTWORK_MEDIA_ORDER_TAKEN(HttpStatus.CONFLICT, "Another preview already uses this position."),
    FILE_CONTENT_MISSING(HttpStatus.NOT_FOUND, "File content is unavailable."),
    ENTITLEMENT_REQUIRED(HttpStatus.FORBIDDEN, "This file is available only to Customers entitled to it."),
    LISTING_INVALID(HttpStatus.BAD_REQUEST, "Listing fields are invalid."),
    LISTING_NOT_FOUND(HttpStatus.NOT_FOUND, "Listing was not found."),
    LISTING_PRODUCT_UNKNOWN(HttpStatus.UNPROCESSABLE_CONTENT, "Manufacturing product is not known."),
    LISTING_VERSION_CONFLICT(HttpStatus.CONFLICT, "Listing was changed by another request; reload and retry."),
    LISTING_NOT_EDITABLE(HttpStatus.CONFLICT, "Moderated or archived Listings cannot be changed."),
    LISTING_STATUS_CHANGE_NOT_ALLOWED(HttpStatus.CONFLICT, "This Listing status change is not allowed."),
    LISTING_ARTWORK_NOT_PUBLISHED(HttpStatus.CONFLICT, "A Listing can be published only under published Artwork."),
    LISTING_PRODUCT_LOCKED(HttpStatus.CONFLICT, "The product cannot change after first publication."),
    LISTING_ASSET_REQUIRED(HttpStatus.CONFLICT, "A published Listing needs at least one file."),
    LISTING_ASSET_MISSING(HttpStatus.CONFLICT, "A file of this Listing is missing from storage."),
    LISTING_ASSETS_LOCKED(HttpStatus.CONFLICT, "Files cannot change after first publication."),
    LISTING_ASSET_LIMIT_REACHED(HttpStatus.CONFLICT, "Listing already has the maximum number of files."),
    LISTING_ASSET_CONFLICT(HttpStatus.CONFLICT, "Listing files were changed by another request; retry."),
    LISTING_ASSET_NOT_FOUND(HttpStatus.NOT_FOUND, "Listing file was not found."),
    LISTING_ASSET_TYPE_NOT_SUPPORTED(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "This file type is not supported."),
    LISTING_ASSET_TOO_LARGE(HttpStatus.CONTENT_TOO_LARGE, "File exceeds the maximum size."),
    LISTING_COST_REVIEW_REQUIRED(HttpStatus.CONFLICT,
        "The manufacturing cost changed; the Artist must review it before this Listing can change or be sold."),
    LISTING_COST_REVIEW_NOT_REQUIRED(HttpStatus.CONFLICT, "This Listing has no manufacturing cost change to review."),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "The request could not be completed.");

    private final HttpStatus status;
    private final String detail;
}
