export class MailEngine {
  async health() { throw new Error("MailEngine.health must be implemented"); }
  async provisionDomain(_input) { throw new Error("MailEngine.provisionDomain must be implemented"); }
  async provisionMailbox(_input) { throw new Error("MailEngine.provisionMailbox must be implemented"); }
  async suspendMailbox(_mailboxId) { throw new Error("MailEngine.suspendMailbox must be implemented"); }
  async deleteMailbox(_mailboxId) { throw new Error("MailEngine.deleteMailbox must be implemented"); }
}

export class UnconfiguredMailEngine extends MailEngine {
  async health() {
    return {
      status: "not_configured",
      transportReady: false,
      protocols: { smtp: false, imap: false },
      message: "Control plane is available; SMTP/IMAP engine has not been connected yet."
    };
  }
}
