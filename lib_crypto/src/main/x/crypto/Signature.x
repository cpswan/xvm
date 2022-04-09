/**
 * Represents the result of a cryptographic signing process.
 */
const Signature
    {
    /**
     * The algorithm used to produce the signature.
     */
    @RO Algorithm algorithm;

    /**
     * The raw bytes of the signature.
     */
    @RO Byte[] bytes;
    }
