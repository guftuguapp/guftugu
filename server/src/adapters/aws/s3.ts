/**
 * AWS adapter — BlobStore on S3 with presigned URLs.
 *
 * The server never proxies bytes: the phone PUTs/GETs straight to S3. Objects
 * are client-encrypted ciphertext; the bucket is private with SSE-S3.
 *
 * The S3Client passed in MUST be created with
 * `requestChecksumCalculation: "WHEN_REQUIRED"` (see ports.ts); otherwise the
 * SDK signs a CRC32 of an empty body into the presigned PUT URL.
 *
 * The presigned PUT signs `Content-Length`, so the phone must send exactly
 * `sizeBytes` bytes — that is how the upload cap is enforced end to end.
 */
import { DeleteObjectCommand, GetObjectCommand, PutObjectCommand, type S3Client } from "@aws-sdk/client-s3";
import { getSignedUrl } from "@aws-sdk/s3-request-presigner";
import type { BlobStore } from "../../core/ports.js";

function expiresInSeconds(ttlMs: number): number {
  // S3 presign minimum is 1 s; cap at 7 days (SigV4 limit).
  return Math.min(7 * 24 * 3600, Math.max(1, Math.floor(ttlMs / 1000)));
}

export function createS3BlobStore(client: S3Client, bucket: string): BlobStore {
  return {
    async presignUpload(key, mime, sizeBytes, ttlMs) {
      const cmd = new PutObjectCommand({
        Bucket: bucket,
        Key: key,
        ContentType: mime,
        ContentLength: sizeBytes,
      });
      const url = await getSignedUrl(client, cmd, { expiresIn: expiresInSeconds(ttlMs) });
      return { url, method: "PUT", headers: { "Content-Type": mime } };
    },
    async presignDownload(key, ttlMs) {
      const cmd = new GetObjectCommand({ Bucket: bucket, Key: key });
      const url = await getSignedUrl(client, cmd, { expiresIn: expiresInSeconds(ttlMs) });
      return { url };
    },
    async delete(key) {
      await client.send(new DeleteObjectCommand({ Bucket: bucket, Key: key }));
    },
  };
}
