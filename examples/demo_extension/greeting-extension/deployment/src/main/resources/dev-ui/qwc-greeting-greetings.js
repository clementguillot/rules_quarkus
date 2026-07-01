import {LitElement, html, css} from 'lit';
import {greetings} from 'build-time-data';

/**
 * Dedicated page listing sample greetings produced by the extension.
 * Accessible at /q/dev-ui/com.example.greeting-extension/greetings
 */
export class QwcGreetingGreetings extends LitElement {

    static styles = css`
        :host {
            display: block;
            padding: 1rem;
        }
        table {
            width: 100%;
            border-collapse: collapse;
        }
        th, td {
            text-align: left;
            padding: 0.5rem 1rem;
            border-bottom: 1px solid var(--lumo-contrast-10pct);
        }
        th {
            font-weight: 600;
            color: var(--lumo-secondary-text-color);
        }
        code {
            background: var(--lumo-contrast-10pct);
            padding: 0.15rem 0.4rem;
            border-radius: 4px;
            font-size: 0.9em;
        }
    `;

    static properties = {
        _greetings: {state: true},
    };

    connectedCallback() {
        super.connectedCallback();
        this._greetings = greetings;
    }

    render() {
        if (!this._greetings || this._greetings.length === 0) {
            return html`<p>No greetings configured.</p>`;
        }
        return html`
            <h3>Sample Greetings</h3>
            <table>
                <thead>
                    <tr>
                        <th>Name</th>
                        <th>Message</th>
                    </tr>
                </thead>
                <tbody>
                    ${this._greetings.map(g => html`
                        <tr>
                            <td><code>${g.name}</code></td>
                            <td>${g.message}</td>
                        </tr>
                    `)}
                </tbody>
            </table>
            <p style="margin-top:1rem; color: var(--lumo-secondary-text-color);">
                Try it: <code>GET /hello?name=YourName</code>
            </p>
        `;
    }
}

customElements.define('qwc-greeting-greetings', QwcGreetingGreetings);
