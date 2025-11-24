#!/usr/bin/env node
import {readFileSync} from 'fs';

interface HookInput {
    session_id: string;
    transcript_path: string;
    cwd: string;
    permission_mode: string;
    prompt: string;
}

interface HashtagMapping {
    hashtag: string;
    skillName: string;
}

async function main() {
    try {
        // Read input from stdin
        const input = process.stdin.isTTY
            ? JSON.stringify({
                session_id: 'test',
                transcript_path: '',
                cwd: process.cwd(),
                permission_mode: 'ask',
                prompt: process.argv[2] || '',
            })
            : readFileSync(0, 'utf-8');

        const data: HookInput = JSON.parse(input);
        const prompt = data.prompt.toLowerCase();

        // Define hashtag to skill mappings
        const hashtagMappings: HashtagMapping[] = [
            {hashtag: '#core', skillName: 'core-dev'},
            {hashtag: '#runner', skillName: 'runner-dev'},
        ];

        // Check for hashtag matches
        const matchedSkills: string[] = [];
        for (const mapping of hashtagMappings) {
            if (prompt.includes(mapping.hashtag)) {
                matchedSkills.push(mapping.skillName);
            }
        }

        // Generate output if matches found
        if (matchedSkills.length > 0) {
            let output = '━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n';
            output += '⚠️ REQUIRED SKILLS:\n';
            matchedSkills.forEach((skillName) => output += `  → ${skillName}\n`);
            output += '━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n\n';
            output += 'ACTION: Use Skill tool BEFORE responding\n';
            output += '━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n';

            console.log(output);
        }

        process.exit(0);
    } catch (err) {
        console.error('Error in skill-activation-prompt hook:', err);
        process.exit(1);
    }
}

main().catch((err) => {
    console.error('Uncaught error:', err);
    process.exit(1);
});
