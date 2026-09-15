export const tukumailContractVersion = "2026-09-15";

export const mailboxAddress = (localPart, domain) => {
  const local = String(localPart || "").trim().toLowerCase();
  const host = String(domain || "").trim().toLowerCase();
  if (!/^[a-z0-9.!#$%&'*+/=?^_`{|}~-]+$/.test(local)) throw new Error("Invalid mailbox local part");
  if (!/^(?=.{1,253}$)(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z]{2,63}$/.test(host)) throw new Error("Invalid mail domain");
  return `${local}@${host}`;
};

export const capabilities = Object.freeze({
  controlPlane: ["organisations", "domains", "mailboxes", "aliases", "quotas", "audit"],
  engine: ["smtp", "imap", "message-store", "delivery-queue"],
  clients: ["web", "android", "desktop"],
  integrations: ["impactos", "jakeos"]
});
